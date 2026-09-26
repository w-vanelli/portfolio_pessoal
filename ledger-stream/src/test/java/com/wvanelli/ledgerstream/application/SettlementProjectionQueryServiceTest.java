package com.wvanelli.ledgerstream.application;

import com.wvanelli.ledgerstream.api.dto.SettlementProjectionResponse;
import com.wvanelli.ledgerstream.domain.SettlementProjection;
import com.wvanelli.ledgerstream.repository.SettlementProjectionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SettlementProjectionQueryServiceTest {

    @Mock
    private SettlementProjectionRepository projectionRepository;

    @InjectMocks
    private SettlementProjectionQueryService queryService;

    @Test
    @DisplayName("findById deve retornar DTO quando registro existir")
    void findByIdShouldReturnDtoWhenFound() {
        UUID messageId = UUID.randomUUID();
        OffsetDateTime acceptedAt = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5);
        OffsetDateTime processedAt = OffsetDateTime.now(ZoneOffset.UTC);
        SettlementProjection projection = new SettlementProjection(
                messageId,
                1001L,
                "ACC-100",
                "USD",
                new BigDecimal("250.75"),
                "WIRE_TRANSFER",
                null,
                acceptedAt,
                processedAt
        );

        when(projectionRepository.findFirstBySettlementIdOrderByProcessedAtDesc(1001L)).thenReturn(Optional.of(projection));

        Optional<SettlementProjectionResponse> response = queryService.findById(1001L);

        assertThat(response).isPresent();
        assertThat(response.get().id()).isEqualTo(1001L);
        assertThat(response.get().settlementId()).isEqualTo(1001L);
        assertThat(response.get().messageId()).isEqualTo(messageId);
        assertThat(response.get().accountId()).isEqualTo("ACC-100");
        assertThat(response.get().currency()).isEqualTo("USD");
        assertThat(response.get().amount()).isEqualTo("250.75");
        assertThat(response.get().settlementType()).isEqualTo("WIRE_TRANSFER");
        assertThat(response.get().originalSettlementId()).isNull();
        assertThat(response.get().acceptedAt()).isEqualTo(acceptedAt);
        assertThat(response.get().processedAt()).isEqualTo(processedAt);

        verify(projectionRepository).findFirstBySettlementIdOrderByProcessedAtDesc(1001L);
    }

    @Test
    @DisplayName("findById deve retornar vazio quando registro nao existir")
    void findByIdShouldReturnEmptyWhenNotFound() {
        when(projectionRepository.findFirstBySettlementIdOrderByProcessedAtDesc(9999L)).thenReturn(Optional.empty());

        Optional<SettlementProjectionResponse> response = queryService.findById(9999L);

        assertThat(response).isEmpty();
        verify(projectionRepository).findFirstBySettlementIdOrderByProcessedAtDesc(9999L);

    }

    @Test
    @DisplayName("findById deve rejeitar IDs nulos ou nao-positivos")
    void findByIdShouldRejectNullOrNonPositiveId() {
        assertThatThrownBy(() -> queryService.findById(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive integer");

        assertThatThrownBy(() -> queryService.findById(0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive integer");

        assertThatThrownBy(() -> queryService.findById(-5L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive integer");

        verifyNoInteractions(projectionRepository);
    }

    @Test
    @DisplayName("findByAccountId deve retornar lista ordenada de projecoes")
    void findByAccountIdShouldReturnOrderedList() {
        UUID m1 = UUID.randomUUID();
        UUID m2 = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        SettlementProjection p1 = new SettlementProjection(
                m1, 2002L, "ACC-200", "BRL", new BigDecimal("500.00"), "CARD_PAYOUT", null, now, now
        );
        SettlementProjection p2 = new SettlementProjection(
                m2, 2001L, "ACC-200", "BRL", new BigDecimal("100.00"), "CARD_PAYOUT", null, now.minusHours(1), now
        );

        when(projectionRepository.findByAccountIdOrderByAcceptedAtDesc("ACC-200")).thenReturn(List.of(p1, p2));

        List<SettlementProjectionResponse> results = queryService.findByAccountId("ACC-200");

        assertThat(results).hasSize(2);
        assertThat(results.get(0).settlementId()).isEqualTo(2002L);
        assertThat(results.get(0).amount()).isEqualTo("500.00");
        assertThat(results.get(1).settlementId()).isEqualTo(2001L);
        assertThat(results.get(1).amount()).isEqualTo("100.00");

        verify(projectionRepository).findByAccountIdOrderByAcceptedAtDesc("ACC-200");
    }

    @Test
    @DisplayName("findByAccountId deve rejeitar accountId nulo ou em branco")
    void findByAccountIdShouldRejectNullOrBlank() {
        assertThatThrownBy(() -> queryService.findByAccountId(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Account ID");

        assertThatThrownBy(() -> queryService.findByAccountId("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Account ID");

        verifyNoInteractions(projectionRepository);
    }
}
