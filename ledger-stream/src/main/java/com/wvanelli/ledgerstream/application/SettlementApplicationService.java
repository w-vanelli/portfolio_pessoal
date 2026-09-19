package com.wvanelli.ledgerstream.application;

import com.wvanelli.ledgerstream.domain.AttachmentStatus;
import com.wvanelli.ledgerstream.domain.MonetaryAmount;
import com.wvanelli.ledgerstream.domain.SettlementAttachment;
import com.wvanelli.ledgerstream.domain.SettlementEvent;
import com.wvanelli.ledgerstream.domain.SettlementStatus;
import com.wvanelli.ledgerstream.domain.SettlementType;
import com.wvanelli.ledgerstream.repository.SettlementEventRepository;
import com.wvanelli.ledgerstream.storage.StagingStorageService;
import com.wvanelli.ledgerstream.storage.StagingTicket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

@Service
public class SettlementApplicationService {

    private static final Logger log = LoggerFactory.getLogger(SettlementApplicationService.class);

    private final SettlementEventRepository repository;
    private final StagingStorageService stagingStorageService;

    public SettlementApplicationService(SettlementEventRepository repository, StagingStorageService stagingStorageService) {
        this.repository = repository;
        this.stagingStorageService = stagingStorageService;
    }

    public SettlementEvent registerSettlement(RegisterSettlementCommand command) throws IOException {
        MonetaryAmount monetaryAmount = new MonetaryAmount(command.amount());

        String checksum = ChecksumGenerator.generatePayloadChecksum(
                command.accountId(), command.currency(), monetaryAmount,
                command.settlementType(), command.description(), command.originalSettlementId(),
                command.attachmentFileName(), command.attachmentContentType()
        );

        Optional<SettlementEvent> existingEventOpt = repository.findByIdempotencyKey(command.idempotencyKey());
        if (existingEventOpt.isPresent()) {
            SettlementEvent existing = existingEventOpt.get();
            if (existing.getPayloadChecksum().equals(checksum)) {
                log.info("Idempotent request received for key {}. Returning existing result.", command.idempotencyKey());
                return existing;
            } else {
                throw new IdempotencyConflictException("Conflict: Idempotency key exists but payload differs.");
            }
        }

        StagingTicket ticket = null;
        if (command.attachmentStream() != null) {
            ticket = stagingStorageService.stage(
                    command.attachmentFileName(),
                    command.attachmentContentType(),
                    command.attachmentStream()
            );
        }

        SettlementEvent savedEvent;
        try {
            savedEvent = persist(command, monetaryAmount, checksum, ticket);
        } catch (DataIntegrityViolationException e) {
            cleanupStaging(ticket);
            return handleConcurrentConstraintViolation(command, checksum);
        } catch (Exception e) {
            cleanupStaging(ticket);
            throw e;
        }

        if (ticket != null) {
            try {
                stagingStorageService.promoteToPermanent(ticket.storagePath());
                // We could update attachment status to PERMANENT here, but rules state: 
                // "Após COMMITTED, falha de promoção ou publicação deve preservar o evento e os arquivos necessários à recuperação."
                // The event stays COMMITTED.
            } catch (IOException e) {
                log.error("Failed to promote staging file after commit. Event remains COMMITTED, recovery needed.", e);
            }
        }

        return savedEvent;
    }

    private SettlementEvent handleConcurrentConstraintViolation(RegisterSettlementCommand command, String checksum) {
        Optional<SettlementEvent> existingEventOpt = repository.findByIdempotencyKey(command.idempotencyKey());
        if (existingEventOpt.isPresent()) {
            SettlementEvent existing = existingEventOpt.get();
            if (existing.getPayloadChecksum().equals(checksum)) {
                log.info("Idempotent request recovered from concurrent constraint violation.");
                return existing;
            } else {
                throw new IdempotencyConflictException("Conflict: Idempotency key exists but payload differs (detected concurrently).");
            }
        }
        throw new IllegalStateException("Failed to recover from constraint violation, record not found.");
    }

    @Transactional
    public SettlementEvent persist(RegisterSettlementCommand command, MonetaryAmount monetaryAmount, String checksum, StagingTicket ticket) {
        if (command.settlementType() == SettlementType.CHARGEBACK_ADJUSTMENT) {
            if (command.originalSettlementId() == null) {
                throw new InvalidChargebackException("Chargeback adjustment must reference an original settlement.");
            }
            SettlementEvent original = repository.findById(command.originalSettlementId())
                    .orElseThrow(() -> new InvalidChargebackException("Original settlement not found."));

            if (original.getSettlementType() == SettlementType.CHARGEBACK_ADJUSTMENT) {
                throw new InvalidChargebackException("Cannot chargeback a chargeback.");
            }
            if (!original.getAccountId().equals(command.accountId())) {
                throw new InvalidChargebackException("Chargeback must belong to the same account as original settlement.");
            }
            if (!original.getCurrency().equals(command.currency())) {
                throw new InvalidChargebackException("Chargeback must have the same currency as original settlement.");
            }
        }

        SettlementEvent event = new SettlementEvent(
                command.idempotencyKey(),
                checksum,
                command.accountId(),
                command.currency(),
                monetaryAmount,
                command.settlementType(),
                command.description()
        );

        if (command.settlementType() == SettlementType.CHARGEBACK_ADJUSTMENT) {
            event.markAsChargebackOf(command.originalSettlementId());
        }

        if (ticket != null) {
            SettlementAttachment attachment = new SettlementAttachment(
                    ticket.originalFileName(),
                    ticket.fileSizeBytes(),
                    ticket.contentType(),
                    ticket.storagePath()
            );
            event.addAttachment(attachment);
        }

        // According to the new logic, the entity starts as STAGED.
        // We explicitly transition it to COMMITTED within the transaction.
        event.transitionTo(SettlementStatus.COMMITTED);
        
        return repository.saveAndFlush(event);
    }

    private void cleanupStaging(StagingTicket ticket) {
        if (ticket == null) return;
        try {
            stagingStorageService.compensateStaging(ticket.storagePath());
        } catch (IOException ioException) {
            log.error("Failed to compensate staging file: {}", ticket.storagePath(), ioException);
        }
    }
}
