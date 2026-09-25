package com.wvanelli.ledgerstream.application;

import com.wvanelli.ledgerstream.domain.ConsumerIdempotencyRecord;
import com.wvanelli.ledgerstream.domain.SettlementAcceptedPayload;
import com.wvanelli.ledgerstream.domain.SettlementProjection;
import com.wvanelli.ledgerstream.repository.ConsumerIdempotencyRepository;
import com.wvanelli.ledgerstream.repository.SettlementProjectionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Service
public class SettlementConsumerService {

    private static final Logger log = LoggerFactory.getLogger(SettlementConsumerService.class);

    private final ConsumerIdempotencyRepository idempotencyRepository;
    private final SettlementProjectionRepository projectionRepository;

    public SettlementConsumerService(ConsumerIdempotencyRepository idempotencyRepository,
                                     SettlementProjectionRepository projectionRepository) {
        this.idempotencyRepository = idempotencyRepository;
        this.projectionRepository = projectionRepository;
    }

    @Transactional
    public void process(SettlementAcceptedPayload payload) {
        if (payload == null || payload.messageId() == null) {
            throw new IllegalArgumentException("Payload or messageId cannot be null");
        }

        if (idempotencyRepository.existsById(payload.messageId())) {
            log.info("Duplicate message {} ignored. Already processed.", payload.messageId());
            return;
        }

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        ConsumerIdempotencyRecord idempotencyRecord = new ConsumerIdempotencyRecord(payload.messageId(), now);
        idempotencyRepository.save(idempotencyRecord);

        SettlementProjection projection = new SettlementProjection(
                payload.messageId(),
                payload.settlementId(),
                payload.accountId(),
                payload.currency(),
                new BigDecimal(payload.amount()),
                payload.settlementType(),
                payload.originalSettlementId(),
                payload.acceptedAt(),
                now
        );
        projectionRepository.save(projection);

        log.info("Successfully processed message {} for settlement {} account {}", 
                payload.messageId(), payload.settlementId(), payload.accountId());
    }
}
