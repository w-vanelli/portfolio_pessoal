package com.wvanelli.ledgerstream.application;

import com.wvanelli.ledgerstream.domain.AttachmentStatus;
import com.wvanelli.ledgerstream.domain.MonetaryAmount;
import com.wvanelli.ledgerstream.domain.SettlementAttachment;
import com.wvanelli.ledgerstream.domain.SettlementEvent;
import com.wvanelli.ledgerstream.domain.SettlementStatus;
import com.wvanelli.ledgerstream.domain.SettlementType;
import com.wvanelli.ledgerstream.domain.SettlementOutboxEntry;
import com.wvanelli.ledgerstream.repository.SettlementEventRepository;
import com.wvanelli.ledgerstream.repository.SettlementOutboxRepository;
import com.wvanelli.ledgerstream.storage.StagingStorageService;
import com.wvanelli.ledgerstream.storage.StagingTicket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class SettlementApplicationService {
    private static final Logger log = LoggerFactory.getLogger(SettlementApplicationService.class);
    private final SettlementEventRepository repository;
    private final SettlementOutboxRepository outboxRepository;
    private final StagingStorageService stagingStorageService;
    private final TransactionTemplate transaction;

    public SettlementApplicationService(SettlementEventRepository repository,
            SettlementOutboxRepository outboxRepository, StagingStorageService stagingStorageService,
            PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.outboxRepository = outboxRepository;
        this.stagingStorageService = stagingStorageService;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public SettlementEvent registerSettlement(RegisterSettlementCommand command) throws IOException {
        // An ambient transaction would postpone commit beyond this method's promotion step.
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Settlement registration must be called outside a transaction");
        }
        Objects.requireNonNull(command, "command");
        MonetaryAmount amount = new MonetaryAmount(command.amount());
        validateCommand(command);
        StagingTicket ticket = command.attachmentStream() == null ? null : stagingStorageService.stage(
                command.attachmentFileName(), command.attachmentContentType(), command.attachmentStream());
        String checksum = ChecksumGenerator.generatePayloadChecksum(
                command.accountId(), command.currency(), amount, command.settlementType(),
                command.description(), command.originalSettlementId(), command.attachmentFileName(),
                command.attachmentContentType(), ticket == null ? null : ticket.contentChecksum());

        Optional<SettlementEvent> existing;
        try {
            existing = repository.findByIdempotencyKey(command.idempotencyKey());
        } catch (RuntimeException failure) {
            cleanupStaging(ticket); // Acceptance has not started.
            throw failure;
        }
        if (existing.isPresent()) {
            cleanupStaging(ticket);
            return replay(existing.get(), checksum);
        }

        AtomicInteger completion = new AtomicInteger(TransactionSynchronization.STATUS_UNKNOWN);
        SettlementEvent saved;
        try {
            saved = transaction.execute(status -> {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int result) { completion.set(result); }
                });
                return persist(command, amount, checksum, ticket);
            });
        } catch (RuntimeException failure) {
            if (completion.get() == TransactionSynchronization.STATUS_ROLLED_BACK) {
                cleanupStaging(ticket);
                if (failure instanceof DataIntegrityViolationException) {
                    Optional<SettlementEvent> winner = repository.findByIdempotencyKey(command.idempotencyKey());
                    if (winner.isPresent()) return replay(winner.get(), checksum);
                }
            } else {
                // A commit exception is not proof of rollback. Do not delete recoverable bytes.
                log.error("Acceptance outcome requires reconciliation: key={}, staging={}",
                        command.idempotencyKey(), ticket == null ? null : ticket.storagePath(), failure);
            }
            throw failure;
        }

        if (ticket != null) promoteAfterCommit(saved, ticket);
        return saved;
    }

    private SettlementEvent replay(SettlementEvent existing, String checksum) {
        if (!existing.getPayloadChecksum().equals(checksum)) {
            throw new IdempotencyConflictException("Idempotency key exists but payload differs");
        }
        return existing;
    }

    private void promoteAfterCommit(SettlementEvent saved, StagingTicket ticket) {
        try {
            Path permanent = stagingStorageService.promoteToPermanent(ticket.storagePath());
            transaction.executeWithoutResult(status -> {
                SettlementEvent persisted = repository.findById(saved.getId()).orElseThrow();
                SettlementAttachment attachment = persisted.getAttachments().stream()
                        .filter(a -> a.getStoragePath().equals(ticket.storagePath())).findFirst().orElseThrow();
                attachment.updateStoragePath(permanent.toString());
                attachment.updateStatus(AttachmentStatus.PERMANENT);
                repository.flush();
            });
            // Reflect the separately committed metadata in the response aggregate.
            saved.getAttachments().forEach(a -> {
                if (a.getStoragePath().equals(ticket.storagePath())) {
                    a.updateStoragePath(permanent.toString());
                    a.updateStatus(AttachmentStatus.PERMANENT);
                }
            });
        } catch (IOException | RuntimeException failure) {
            log.error("Post-commit promotion/metadata pending: event={}, staging={}. Preserve files for reconciliation.",
                    saved.getId(), ticket.storagePath(), failure);
        }
    }

    private void validateCommand(RegisterSettlementCommand command) {
        Objects.requireNonNull(command.idempotencyKey(), "idempotencyKey");
        Objects.requireNonNull(command.settlementType(), "settlementType");
        if (command.accountId() == null || command.accountId().isBlank() || command.accountId().length() > 64
                || command.currency() == null || !command.currency().matches("[A-Z]{3}")
                || (command.description() != null && command.description().length() > 255)) {
            throw new IllegalArgumentException("Invalid settlement metadata");
        }
        if (command.settlementType() != SettlementType.CHARGEBACK_ADJUSTMENT && command.originalSettlementId() != null) {
            throw new InvalidChargebackException("Only chargebacks may reference an original settlement");
        }
        if (command.attachmentStream() == null
                && (command.attachmentFileName() != null || command.attachmentContentType() != null)) {
            throw new IllegalArgumentException("Attachment metadata requires an attachment stream");
        }
    }

    private SettlementEvent persist(RegisterSettlementCommand command, MonetaryAmount monetaryAmount,
                                     String checksum, StagingTicket ticket) {
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
        
        SettlementEvent saved = repository.saveAndFlush(event);
        // The outbox row must be part of this same transaction. If this write fails,
        // acceptance rolls back and the staged attempt is eligible for compensation.
        outboxRepository.saveAndFlush(new SettlementOutboxEntry(saved));
        return saved;
    }

    private void cleanupStaging(StagingTicket ticket) {
        if (ticket == null) return;
        try {
            stagingStorageService.compensateStaging(ticket.storagePath());
        } catch (IOException failure) {
            log.error("Failed to compensate staging file: {}", ticket.storagePath(), failure);
        }
    }
}
