package com.wvanelli.ledgerstream.application;

import com.wvanelli.ledgerstream.domain.MonetaryAmount;
import com.wvanelli.ledgerstream.domain.SettlementEvent;
import com.wvanelli.ledgerstream.domain.SettlementStatus;
import com.wvanelli.ledgerstream.domain.SettlementType;
import com.wvanelli.ledgerstream.repository.SettlementEventRepository;
import com.wvanelli.ledgerstream.storage.StagingStorageService;
import com.wvanelli.ledgerstream.storage.StagingTicket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import java.nio.file.Path;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SettlementApplicationServiceTest {

    @Mock
    private SettlementEventRepository repository;

    @Mock
    private StagingStorageService stagingStorageService;

    private SettlementApplicationService service;

    private TestTransactionManager transactionManager;
    private UUID key;
    private RegisterSettlementCommand command;

    @BeforeEach
    void setUp() {
        transactionManager = new TestTransactionManager();
        service = new SettlementApplicationService(repository, stagingStorageService, transactionManager);
        key = UUID.randomUUID();
        command = new RegisterSettlementCommand(
                key, "ACC-123", "USD", "100.00", SettlementType.CARD_PAYOUT,
                "Test", null, null, null, null
        );
    }

    @Test
    @DisplayName("Should process new settlement and promote staging file on success")
    void shouldProcessNewSettlement() throws IOException {
        RegisterSettlementCommand cmdWithFile = new RegisterSettlementCommand(
                key, "ACC-123", "USD", "100.00", SettlementType.CARD_PAYOUT,
                "Test", null, "file.txt", "text/plain", new ByteArrayInputStream(new byte[0])
        );

        StagingTicket ticket = new StagingTicket("temp/file.txt", 100L, "text/plain", "file.txt", "a".repeat(64));
        when(stagingStorageService.stage(any(), any(), any())).thenReturn(ticket);
        when(repository.findByIdempotencyKey(key)).thenReturn(Optional.empty());

        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(stagingStorageService.promoteToPermanent("temp/file.txt")).thenAnswer(invocation -> {
            assertThat(transactionManager.committed).isTrue();
            return Path.of("permanent/file.txt");
        });
        when(repository.findById(any())).thenAnswer(invocation -> {
            SettlementEvent persisted = new SettlementEvent(key, "dummy", "ACC-123", "USD",
                    new MonetaryAmount("100"), SettlementType.CARD_PAYOUT, "Test");
            persisted.addAttachment(new com.wvanelli.ledgerstream.domain.SettlementAttachment(
                    "file.txt", 100L, "text/plain", "temp/file.txt"));
            return Optional.of(persisted);
        });

        SettlementEvent result = service.registerSettlement(cmdWithFile);

        assertThat(result).isNotNull();
        verify(stagingStorageService).stage(eq("file.txt"), eq("text/plain"), any());
        verify(repository).saveAndFlush(any(SettlementEvent.class));
        verify(stagingStorageService).promoteToPermanent("temp/file.txt");
        verify(stagingStorageService, never()).compensateStaging(any());
    }

    @Test
    @DisplayName("Should return existing settlement if idempotent request has same payload")
    void shouldReturnExistingIdempotent() throws IOException {
        String checksum = ChecksumGenerator.generatePayloadChecksum(
                "ACC-123", "USD", new MonetaryAmount("100.00"), SettlementType.CARD_PAYOUT, "Test", null, null, null, null
        );
        SettlementEvent existing = new SettlementEvent(key, checksum, "ACC-123", "USD", new MonetaryAmount("100.00"), SettlementType.CARD_PAYOUT, "Test");

        when(repository.findByIdempotencyKey(key)).thenReturn(Optional.of(existing));

        SettlementEvent result = service.registerSettlement(command);

        assertThat(result).isEqualTo(existing);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Should throw exception if idempotent request has different payload")
    void shouldThrowIdempotentConflict() {
        SettlementEvent existing = new SettlementEvent(key, "different_checksum", "ACC-123", "USD", new MonetaryAmount("100.00"), SettlementType.CARD_PAYOUT, "Test");
        when(repository.findByIdempotencyKey(key)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.registerSettlement(command))
                .isInstanceOf(IdempotencyConflictException.class);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Should compensate staging and recover on concurrent constraint violation")
    void shouldRecoverFromConcurrentConstraintViolation() throws IOException {
        String checksum = ChecksumGenerator.generatePayloadChecksum(
                "ACC-123", "USD", new MonetaryAmount("100.00"), SettlementType.CARD_PAYOUT, "Test", null, "file.txt", "text/plain", "a".repeat(64)
        );
        SettlementEvent existing = new SettlementEvent(key, checksum, "ACC-123", "USD", new MonetaryAmount("100.00"), SettlementType.CARD_PAYOUT, "Test");

        RegisterSettlementCommand cmdWithFile = new RegisterSettlementCommand(
                key, "ACC-123", "USD", "100.00", SettlementType.CARD_PAYOUT,
                "Test", null, "file.txt", "text/plain", new ByteArrayInputStream(new byte[0])
        );
        StagingTicket ticket = new StagingTicket("temp/file.txt", 100L, "text/plain", "file.txt", "a".repeat(64));

        when(stagingStorageService.stage(any(), any(), any())).thenReturn(ticket);
        
        // First check returns empty (simulating concurrent insert)
        when(repository.findByIdempotencyKey(key))
                .thenReturn(Optional.empty()) // Before stage
                .thenReturn(Optional.of(existing)); // During recovery

        when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("Unique constraint"));

        SettlementEvent result = service.registerSettlement(cmdWithFile);

        assertThat(result).isEqualTo(existing);
        verify(stagingStorageService).compensateStaging("temp/file.txt"); // It must cleanup the losing attempt's file
        verify(stagingStorageService, never()).promoteToPermanent(any());
    }

    @Test
    @DisplayName("Should compensate staging on save failure")
    void shouldCompensateOnSaveFailure() throws IOException {
        RegisterSettlementCommand cmdWithFile = new RegisterSettlementCommand(
                key, "ACC-123", "USD", "100.00", SettlementType.CARD_PAYOUT,
                "Test", null, "file.txt", "text/plain", new ByteArrayInputStream(new byte[0])
        );
        StagingTicket ticket = new StagingTicket("temp/file.txt", 100L, "text/plain", "file.txt", "a".repeat(64));

        when(stagingStorageService.stage(any(), any(), any())).thenReturn(ticket);
        when(repository.findByIdempotencyKey(key)).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any())).thenThrow(new RuntimeException("DB down"));

        assertThatThrownBy(() -> service.registerSettlement(cmdWithFile))
                .isInstanceOf(RuntimeException.class);

        verify(stagingStorageService).compensateStaging("temp/file.txt");
        verify(stagingStorageService, never()).promoteToPermanent(any());
    }

    @Test
    @DisplayName("Should not delete staging on promotion failure but leave DB COMMITTED")
    void shouldNotDeleteOnPromotionFailure() throws IOException {
        RegisterSettlementCommand cmdWithFile = new RegisterSettlementCommand(
                key, "ACC-123", "USD", "100.00", SettlementType.CARD_PAYOUT,
                "Test", null, "file.txt", "text/plain", new ByteArrayInputStream(new byte[0])
        );
        StagingTicket ticket = new StagingTicket("temp/file.txt", 100L, "text/plain", "file.txt", "a".repeat(64));

        when(stagingStorageService.stage(any(), any(), any())).thenReturn(ticket);
        when(repository.findByIdempotencyKey(key)).thenReturn(Optional.empty());
        
        SettlementEvent mockSavedEvent = new SettlementEvent(key, "dummy", "ACC-123", "USD", new MonetaryAmount("100.00"), SettlementType.CARD_PAYOUT, "Test");
        mockSavedEvent.transitionTo(SettlementStatus.COMMITTED);
        when(repository.saveAndFlush(any())).thenReturn(mockSavedEvent);

        when(stagingStorageService.promoteToPermanent("temp/file.txt")).thenThrow(new IOException("Disk full"));

        SettlementEvent result = service.registerSettlement(cmdWithFile);

        assertThat(result.getStatus()).isEqualTo(SettlementStatus.COMMITTED);
        verify(stagingStorageService, never()).compensateStaging(any()); // Should preserve files needed for recovery
    }

    @Test
    @DisplayName("Chargeback must reference original settlement")
    void chargebackValidationNoOriginal() {
        RegisterSettlementCommand cmd = new RegisterSettlementCommand(
                key, "ACC-123", "USD", "100.00", SettlementType.CHARGEBACK_ADJUSTMENT,
                "Test", null, null, null, null
        );

        when(repository.findByIdempotencyKey(key)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.registerSettlement(cmd))
                .isInstanceOf(InvalidChargebackException.class)
                .hasMessageContaining("must reference an original settlement");
    }

    @Test
    @DisplayName("Chargeback must match original account and currency")
    void chargebackValidationAccountMismatch() {
        RegisterSettlementCommand cmd = new RegisterSettlementCommand(
                key, "ACC-123", "USD", "100.00", SettlementType.CHARGEBACK_ADJUSTMENT,
                "Test", 999L, null, null, null
        );

        SettlementEvent original = new SettlementEvent(UUID.randomUUID(), "c", "ACC-999", "USD", new MonetaryAmount("100.00"), SettlementType.CARD_PAYOUT, "");
        
        when(repository.findByIdempotencyKey(key)).thenReturn(Optional.empty());
        when(repository.findById(999L)).thenReturn(Optional.of(original));

        assertThatThrownBy(() -> service.registerSettlement(cmd))
                .isInstanceOf(InvalidChargebackException.class)
                .hasMessageContaining("must belong to the same account");
    }

    @Test
    void shouldPreserveStagingWhenCommitOutcomeIsUnknown() throws IOException {
        var withFile = new RegisterSettlementCommand(key, "ACC-123", "USD", "100",
                SettlementType.CARD_PAYOUT, "Test", null, "file.txt", "text/plain",
                new ByteArrayInputStream(new byte[0]));
        when(stagingStorageService.stage(any(), any(), any())).thenReturn(
                new StagingTicket("temp/file.txt", 0, "text/plain", "file.txt", "a".repeat(64)));
        when(repository.findByIdempotencyKey(key)).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        transactionManager.failCommit = true;
        assertThatThrownBy(() -> service.registerSettlement(withFile)).isInstanceOf(TransactionSystemException.class);
        verify(stagingStorageService, never()).compensateStaging(any());
        verify(stagingStorageService, never()).promoteToPermanent(any());
    }

    @Test
    void shouldPreserveOriginalIntegrityFailureWhenNoWinnerExists() {
        when(repository.findByIdempotencyKey(key)).thenReturn(Optional.empty());
        var failure = new DataIntegrityViolationException("unrelated constraint");
        when(repository.saveAndFlush(any())).thenThrow(failure);
        assertThatThrownBy(() -> service.registerSettlement(command)).isSameAs(failure);
    }

    @Test
    void shouldRejectAmbientTransactionBeforeReadingTheStream() {
        new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> assertThatThrownBy(() -> service.registerSettlement(command))
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining("outside a transaction"));
        verifyNoInteractions(repository, stagingStorageService);
    }

    // Exercises Spring's real completion callbacks with simulated commit/rollback outcomes.
    // PostgreSQL behavior is covered separately by *IT tests.
    static class TestTransactionManager extends AbstractPlatformTransactionManager {
        boolean committed;
        boolean failCommit;
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object tx, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) {
            if (failCommit) throw new TransactionSystemException("Commit acknowledgement lost");
            committed = true;
        }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
    }
}
