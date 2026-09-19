package com.wvanelli.ledgerstream.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettlementEventTest {

    @Test
    @DisplayName("Should instantiate SettlementEvent with STAGED initial status and valid fields")
    void shouldInstantiateSettlementEvent() {
        UUID idempotencyKey = UUID.randomUUID();
        String payloadChecksum = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        MonetaryAmount amount = new MonetaryAmount("1250.00");

        SettlementEvent event = new SettlementEvent(
                idempotencyKey,
                payloadChecksum,
                "ACC-1001",
                "USD",
                amount,
                SettlementType.INVOICE_SETTLEMENT,
                "Test payout"
        );

        assertThat(event.getIdempotencyKey()).isEqualTo(idempotencyKey);
        assertThat(event.getPayloadChecksum()).isEqualTo(payloadChecksum);
        assertThat(event.getAccountId()).isEqualTo("ACC-1001");
        assertThat(event.getCurrency()).isEqualTo("USD");
        assertThat(event.getMonetaryAmount()).isEqualTo(amount);
        assertThat(event.getSettlementType()).isEqualTo(SettlementType.INVOICE_SETTLEMENT);
        assertThat(event.getDescription()).isEqualTo("Test payout");
        assertThat(event.getStatus()).isEqualTo(SettlementStatus.STAGED);
        assertThat(event.getCreatedAt()).isNotNull();
        assertThat(event.getUpdatedAt()).isEqualTo(event.getCreatedAt());
        assertThat(event.getAttachments()).isEmpty();
        assertThat(event.getOriginalSettlementId()).isNull();
    }

    @Test
    @DisplayName("Should transition status and update updatedAt timestamp for valid transitions")
    void shouldTransitionStatus() {
        SettlementEvent event = new SettlementEvent(
                UUID.randomUUID(),
                "checksum123",
                "ACC-1001",
                "USD",
                new MonetaryAmount("100.00"),
                SettlementType.CARD_PAYOUT,
                "Payout"
        );

        event.transitionTo(SettlementStatus.COMMITTED);
        assertThat(event.getStatus()).isEqualTo(SettlementStatus.COMMITTED);

        event.transitionTo(SettlementStatus.DISPATCHED);
        assertThat(event.getStatus()).isEqualTo(SettlementStatus.DISPATCHED);
    }

    @Test
    @DisplayName("Should prevent invalid state transitions")
    void shouldPreventInvalidTransitions() {
        SettlementEvent event = createDefaultEvent(); // STAGED
        
        assertThatThrownBy(() -> event.transitionTo(SettlementStatus.DISPATCHED))
                .isInstanceOf(IllegalStateException.class);
        
        event.transitionTo(SettlementStatus.COMMITTED);
        
        assertThatThrownBy(() -> event.transitionTo(SettlementStatus.STAGED))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> event.transitionTo(SettlementStatus.FAILED))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> event.transitionTo(SettlementStatus.COMPENSATED))
                .isInstanceOf(IllegalStateException.class);
                
        event.transitionTo(SettlementStatus.DISPATCHED);
        
        assertThatThrownBy(() -> event.transitionTo(SettlementStatus.STAGED))
                .isInstanceOf(IllegalStateException.class);
    }
    
    @Test
    @DisplayName("Should allow transition to same state without updating timestamp")
    void shouldAllowSameStateTransition() throws InterruptedException {
        SettlementEvent event = createDefaultEvent(); // STAGED
        var originalUpdatedAt = event.getUpdatedAt();

        Thread.sleep(5);

        event.transitionTo(SettlementStatus.STAGED);
        assertThat(event.getStatus()).isEqualTo(SettlementStatus.STAGED);
        assertThat(event.getUpdatedAt()).isEqualTo(originalUpdatedAt); // Should not update
    }

    @Test
    @DisplayName("Should associate attachment bidirectionally")
    void shouldAssociateAttachment() {
        SettlementEvent event = new SettlementEvent(
                UUID.randomUUID(),
                "checksum123",
                "ACC-1001",
                "BRL",
                new MonetaryAmount("500.00"),
                SettlementType.WIRE_TRANSFER,
                "Doc settlement"
        );

        SettlementAttachment attachment = new SettlementAttachment(
                "receipt.pdf",
                1024L,
                "application/pdf",
                "/storage/staging/receipt-xyz.tmp"
        );

        event.addAttachment(attachment);

        assertThat(event.getAttachments()).hasSize(1);
        assertThat(event.getAttachments().get(0)).isEqualTo(attachment);
        assertThat(attachment.getSettlementEvent()).isEqualTo(event);
        assertThat(attachment.getStatus()).isEqualTo(AttachmentStatus.STAGED);
    }

    @Test
    @DisplayName("Should reject invalid currency codes")
    void shouldRejectInvalidCurrency() {
        assertThatThrownBy(() -> new SettlementEvent(
                UUID.randomUUID(),
                "checksum123",
                "ACC-1001",
                "US", // Only 2 chars
                new MonetaryAmount("100.00"),
                SettlementType.CARD_PAYOUT,
                "Payout"
        )).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Currency must be a 3-letter ISO code");
    }

    @Test
    @DisplayName("Should reject null required constructor arguments")
    void shouldRejectNullConstructorArguments() {
        MonetaryAmount amount = new MonetaryAmount("100.00");

        assertThatThrownBy(() -> new SettlementEvent(
                null, "checksum", "ACC-1001", "USD", amount, SettlementType.CARD_PAYOUT, "desc"
        )).isInstanceOf(NullPointerException.class).hasMessageContaining("idempotencyKey");

        assertThatThrownBy(() -> new SettlementEvent(
                UUID.randomUUID(), null, "ACC-1001", "USD", amount, SettlementType.CARD_PAYOUT, "desc"
        )).isInstanceOf(NullPointerException.class).hasMessageContaining("payloadChecksum");

        assertThatThrownBy(() -> new SettlementEvent(
                UUID.randomUUID(), "checksum", null, "USD", amount, SettlementType.CARD_PAYOUT, "desc"
        )).isInstanceOf(NullPointerException.class).hasMessageContaining("accountId");

        assertThatThrownBy(() -> new SettlementEvent(
                UUID.randomUUID(), "checksum", "ACC-1001", null, amount, SettlementType.CARD_PAYOUT, "desc"
        )).isInstanceOf(NullPointerException.class).hasMessageContaining("currency");

        assertThatThrownBy(() -> new SettlementEvent(
                UUID.randomUUID(), "checksum", "ACC-1001", "USD", null, SettlementType.CARD_PAYOUT, "desc"
        )).isInstanceOf(NullPointerException.class).hasMessageContaining("monetaryAmount");

        assertThatThrownBy(() -> new SettlementEvent(
                UUID.randomUUID(), "checksum", "ACC-1001", "USD", amount, null, "desc"
        )).isInstanceOf(NullPointerException.class).hasMessageContaining("settlementType");
    }

    @Test
    @DisplayName("transitionTo should reject null status")
    void shouldRejectNullTransition() {
        SettlementEvent event = createDefaultEvent();
        assertThatThrownBy(() -> event.transitionTo(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("transitionTo should update updatedAt timestamp on each transition")
    void shouldUpdateTimestampOnTransition() throws InterruptedException {
        SettlementEvent event = createDefaultEvent();
        var originalUpdatedAt = event.getUpdatedAt();

        // Small delay to ensure timestamps differ
        Thread.sleep(5);

        event.transitionTo(SettlementStatus.COMMITTED);
        assertThat(event.getUpdatedAt()).isAfterOrEqualTo(originalUpdatedAt);
    }

    @Test
    @DisplayName("originalSettlementId should be null for non-chargeback events")
    void shouldHaveNullOriginalSettlementIdForNonChargebacks() {
        SettlementEvent event = new SettlementEvent(
                UUID.randomUUID(),
                "checksum456",
                "ACC-2002",
                "EUR",
                new MonetaryAmount("750.50"),
                SettlementType.CARD_PAYOUT,
                "Regular card payout"
        );

        assertThat(event.getOriginalSettlementId()).isNull();
    }

    private SettlementEvent createDefaultEvent() {
        return new SettlementEvent(
                UUID.randomUUID(),
                "default-checksum",
                "ACC-TEST",
                "USD",
                new MonetaryAmount("100.00"),
                SettlementType.CARD_PAYOUT,
                "Test event"
        );
    }
}
