package com.wvanelli.ledgerstream.application;

import com.wvanelli.ledgerstream.AbstractIntegrationTest;
import com.wvanelli.ledgerstream.domain.*;
import com.wvanelli.ledgerstream.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** No test-level transaction: these assertions observe real service commits. */
class SettlementApplicationServiceIT extends AbstractIntegrationTest {
    @Autowired SettlementApplicationService service;
    @Autowired SettlementEventRepository events;
    @Autowired SettlementAttachmentRepository attachments;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    private RegisterSettlementCommand command(UUID key, String amount, String content) {
        return new RegisterSettlementCommand(key, "SYNTH-APP", "USD", amount, SettlementType.CARD_PAYOUT,
                "Synthetic attachment", null, "proof.txt", "text/plain", new ByteArrayInputStream(content.getBytes()));
    }

    @Test void commitsEventAndPromotedAttachmentMetadata() throws Exception {
        var event = service.registerSettlement(command(UUID.randomUUID(), "1", "proof"));
        assertThat(events.findById(event.getId()).orElseThrow().getStatus()).isEqualTo(SettlementStatus.COMMITTED);
        var attachment = attachments.findBySettlementEventId(event.getId()).getFirst();
        assertThat(attachment.getStatus()).isEqualTo(AttachmentStatus.PERMANENT);
        assertThat(Path.of(attachment.getStoragePath())).hasContent("proof");
        assertThat(service.registerSettlement(command(event.getIdempotencyKey(), "1.00", "proof")).getId())
                .isEqualTo(event.getId());
        assertThatThrownBy(() -> service.registerSettlement(command(event.getIdempotencyKey(), "1", "changed")))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThat(Path.of(attachment.getStoragePath())).hasContent("proof");
    }

    @Test void concurrentEquivalentRequestsKeepOneEventAndWinningAttachment() throws Exception {
        UUID key = UUID.randomUUID();
        var barrier = new CyclicBarrier(8);
        try (var executor = Executors.newFixedThreadPool(8)) {
            List<Future<Long>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) results.add(executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return service.registerSettlement(command(key, "2.00", "same synthetic proof")).getId();
            }));
            Set<Long> ids = new HashSet<>();
            for (var result : results) ids.add(result.get(30, TimeUnit.SECONDS));
            assertThat(ids).hasSize(1);
            Long id = ids.iterator().next();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM settlement_events WHERE idempotency_key = ?", Long.class, key))
                    .isEqualTo(1L);
            var files = attachments.findBySettlementEventId(id);
            assertThat(files).hasSize(1);
            assertThat(files.getFirst().getStatus()).isEqualTo(AttachmentStatus.PERMANENT);
            assertThat(Path.of(files.getFirst().getStoragePath())).hasContent("same synthetic proof");
        }
    }

    @Test void chargebackCreatesNewEventAndPreservesOriginal() throws Exception {
        var original = service.registerSettlement(command(UUID.randomUUID(), "3", "original"));
        var chargeback = new RegisterSettlementCommand(UUID.randomUUID(), "SYNTH-APP", "USD", "1",
                SettlementType.CHARGEBACK_ADJUSTMENT, "Synthetic adjustment", original.getId(), null, null, null);
        var result = service.registerSettlement(chargeback);
        assertThat(result.getId()).isNotEqualTo(original.getId());
        assertThat(result.getOriginalSettlementId()).isEqualTo(original.getId());
        assertThat(events.findById(original.getId()).orElseThrow().getAmount()).isEqualByComparingTo("3.00");
    }

    @Test void refusesAmbientTransactionWithoutAcceptingEvent() {
        UUID key = UUID.randomUUID();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertThatThrownBy(() -> service.registerSettlement(command(key, "1", "proof")))
                    .isInstanceOf(IllegalStateException.class);
            status.setRollbackOnly();
        });
        assertThat(events.findByIdempotencyKey(key)).isEmpty();
    }
}
