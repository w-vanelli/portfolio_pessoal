package com.wvanelli.ledgerstream.application;

import com.wvanelli.ledgerstream.AbstractMessagingIntegrationTest;
import com.wvanelli.ledgerstream.domain.MonetaryAmount;
import com.wvanelli.ledgerstream.domain.SettlementType;
import com.wvanelli.ledgerstream.repository.SettlementEventRepository;
import com.wvanelli.ledgerstream.repository.SettlementProjectionRepository;
import com.wvanelli.ledgerstream.repository.SettlementOutboxRepository;
import com.wvanelli.ledgerstream.storage.StagingStorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.TimeUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(properties = {
    // Override abstract config to enable the dispatcher scheduler
    "ledgerstream.dispatcher.poll-interval-ms=500"
})
public class EndToEndSettlementIT extends AbstractMessagingIntegrationTest {

    @Autowired
    private SettlementApplicationService applicationService;

    @Autowired
    private OutboxDispatcherService dispatcherService;

    @Autowired
    private SettlementProjectionRepository projectionRepository;

    @Autowired
    private SettlementEventRepository eventRepository;

    @Autowired
    private SettlementOutboxRepository outboxRepository;

    @Autowired
    private SettlementProjectionQueryService queryService;

    @AfterEach
    void tearDown() {
        projectionRepository.deleteAll();
        truncateEvents();
    }

    @Test
    @DisplayName("Fluxo completo: Service -> Outbox -> RabbitMQ -> Consumer -> Projeção -> Query Service")
    void shouldProcessEndToEnd() throws Exception {
        // 1. Arrange & Act: Call application service to accept settlement
        var command = new com.wvanelli.ledgerstream.application.RegisterSettlementCommand(
                java.util.UUID.randomUUID(),
                "ACC-E2E-1",
                "USD",
                "150.00",
                SettlementType.WIRE_TRANSFER,
                "E2E Integration Test",
                null,
                null,
                null,
                null
        );
        var receipt = applicationService.registerSettlement(command);

        // 2. Assert: Wait for projection to be created
        // The dispatcher scheduler will pick it up (poll=500ms), send to RabbitMQ, consumer will read and save projection.
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            var projections = projectionRepository.findByAccountId("ACC-E2E-1");
            assertThat(projections).hasSize(1);
            assertThat(projections.get(0).getAmount()).isEqualByComparingTo("150.00");
            assertThat(projections.get(0).getSettlementId()).isEqualTo(receipt.getId());

            var queryResult = queryService.findById(receipt.getId());
            assertThat(queryResult).isPresent();
            assertThat(queryResult.get().id()).isEqualTo(receipt.getId());
            assertThat(queryResult.get().accountId()).isEqualTo("ACC-E2E-1");
            assertThat(queryResult.get().amount()).isEqualTo("150.00");
        });
    }
}

