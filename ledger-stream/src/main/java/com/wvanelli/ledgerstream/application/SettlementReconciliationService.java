package com.wvanelli.ledgerstream.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wvanelli.ledgerstream.domain.*;
import com.wvanelli.ledgerstream.repository.*;
import com.wvanelli.ledgerstream.storage.StagingStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.function.Supplier;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

/** Recovery from persisted producer state. Never infers rollback from a database failure. */
@Service
public class SettlementReconciliationService {
    private static final Logger log = LoggerFactory.getLogger(SettlementReconciliationService.class);
    private final SettlementEventRepository events;
    private final SettlementAttachmentRepository attachments;
    private final SettlementOutboxRepository outbox;
    private final StagingStorageService storage;
    private final TransactionTemplate transaction;
    private final ObjectMapper mapper;
    private final ReconciliationProperties properties;

    public SettlementReconciliationService(SettlementEventRepository events,
            SettlementAttachmentRepository attachments, SettlementOutboxRepository outbox,
            StagingStorageService storage, PlatformTransactionManager transactionManager,
            ObjectMapper mapper, ReconciliationProperties properties) {
        this.events = events;
        this.attachments = attachments;
        this.outbox = outbox;
        this.storage = storage;
        this.mapper = mapper;
        this.properties = properties;
        transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Scheduled(fixedDelayString = "${ledgerstream.reconciliation.poll-interval-ms:60000}",
            initialDelayString = "${ledgerstream.reconciliation.initial-delay-ms:10000}")
    public void scheduledReconciliation() {
        if (properties.isEnabled()) reconcileAll();
    }

    public ReconciliationReport reconcileAll() {
        Instant start = Instant.now();
        List<String> failures = new ArrayList<>();
        int orphans = reconcileOrphans(Duration.ofSeconds(properties.getOrphanGracePeriodSeconds()), failures);
        int staged = reconcileAttachments(failures);
        int integrity = reconcileIntegrity(failures);
        var report = new ReconciliationReport(start, Instant.now(), orphans, staged, integrity, failures);
        log.info("Reconciliation completed: {}", report);
        return report;
    }

    public int reconcileOrphanStagingFiles(Duration gracePeriod) {
        return reconcileOrphans(gracePeriod, new ArrayList<>());
    }

    public int reconcileStagedAttachments() { return reconcileAttachments(new ArrayList<>()); }
    public int reconcileOutboxIntegrity() { return reconcileIntegrity(new ArrayList<>()); }

    private int reconcileOrphans(Duration gracePeriod, List<String> failures) {
        Objects.requireNonNull(gracePeriod, "gracePeriod");
        if (gracePeriod.isNegative()) throw new IllegalArgumentException("Negative grace period");
        Instant cutoff = Instant.now().minus(gracePeriod);
        List<Path> candidates;
        try { candidates = storage.listStagingAttemptDirectories(); }
        catch (Exception ex) { failure(failures, "orphan scan", ex); return 0; }
        int changed = 0;
        for (Path directory : candidates) {
            changed += isolated(failures, "orphan " + directory, () -> {
                try {
                    String path = directory.resolve("content").toString();
                    // Validate both roots, the owned UUID and content before inspecting age.
                    storage.isStaged(path);
                    if (!oldEnough(directory, cutoff)) return 0;
                    return transaction.execute(status -> {
                        // A database error escapes and prevents deletion. Recheck age after the query.
                        if (attachments.existsByStoragePath(path)) return 0;
                        try {
                            if (!oldEnough(directory, cutoff)) return 0;
                            storage.cleanupOrphanStagingDirectory(directory);
                            return 1;
                        } catch (IOException ex) { throw new UncheckedIOException(ex); }
                    });
                } catch (IOException ex) { throw new UncheckedIOException(ex); }
            });
        }
        return changed;
    }

    private boolean oldEnough(Path directory, Instant cutoff) throws IOException {
        BasicFileAttributes dir = Files.readAttributes(directory, BasicFileAttributes.class, NOFOLLOW_LINKS);
        if (!dir.isDirectory() || dir.isSymbolicLink() || !older(dir, cutoff)) return false;
        try {
            BasicFileAttributes file = Files.readAttributes(directory.resolve("content"),
                    BasicFileAttributes.class, NOFOLLOW_LINKS);
            return file.isRegularFile() && !file.isSymbolicLink() && older(file, cutoff);
        } catch (NoSuchFileException absent) { return true; }
    }

    private boolean older(BasicFileAttributes attrs, Instant cutoff) {
        // Creation time protects newly recreated/copied paths with old modification timestamps.
        return attrs.lastModifiedTime().toInstant().isBefore(cutoff)
                && attrs.creationTime().toInstant().isBefore(cutoff);
    }

    private int reconcileAttachments(List<String> failures) {
        List<SettlementAttachment> candidates;
        try { candidates = attachments.findByStatus(AttachmentStatus.STAGED); }
        catch (Exception ex) { failure(failures, "attachment scan", ex); return 0; }
        int changed = 0;
        for (SettlementAttachment candidate : candidates) {
            changed += isolated(failures, "attachment " + candidate.getId(), () -> transaction.execute(status -> {
                // Lock order shared with dispatcher: outbox, event, then fresh attachment state.
                Long eventId = candidate.getSettlementEvent().getId();
                var entry = outbox.findLockedBySettlementId(eventId);
                var event = events.findLockedById(eventId).orElseThrow();
                var current = attachments.findBySettlementEventId(eventId).stream()
                        .filter(a -> a.getId().equals(candidate.getId())).findFirst();
                if (current.isEmpty() || current.get().getStatus() != AttachmentStatus.STAGED) return 0;
                SettlementAttachment attachment = current.get();
                try {
                    String path = attachment.getStoragePath();
                    if (event.getStatus() == SettlementStatus.FAILED || event.getStatus() == SettlementStatus.COMPENSATED) {
                        storage.compensateStaging(path); // Never touches permanent bytes.
                        attachment.updateStatus(AttachmentStatus.PURGED);
                    } else if (event.getStatus() == SettlementStatus.COMMITTED || event.getStatus() == SettlementStatus.DISPATCHED) {
                        Path permanent;
                        if (storage.isPermanentlyStored(path)) {
                            permanent = storage.getPermanentPath(path);
                        } else if (storage.isStaged(path)) {
                            permanent = storage.promoteToPermanent(path);
                        } else {
                            throw new IOException("Content absent in staging and permanent storage; reference preserved: " + path);
                        }
                        // Only empty staging is cleaned; duplicate bytes are retained for investigation.
                        if (!storage.isStaged(path)) storage.cleanupOrphanStagingDirectory(Path.of(path).getParent());
                        attachment.updateStoragePath(permanent.toString());
                        attachment.updateStatus(AttachmentStatus.PERMANENT);
                    } else { return 0; }
                    attachments.saveAndFlush(attachment);
                    if (attachment.getStatus() == AttachmentStatus.PERMANENT
                            && entry.isPresent() && entry.get().getStatus() == OutboxStatus.PENDING
                            && attachments.findBySettlementEventId(eventId).stream()
                                .allMatch(a -> a.getStatus() == AttachmentStatus.PERMANENT)) {
                        entry.get().deferWithoutAttempt(OffsetDateTime.now(ZoneOffset.UTC));
                        outbox.saveAndFlush(entry.get());
                    }
                    return 1;
                } catch (IOException ex) { throw new UncheckedIOException(ex); }
            }));
        }
        return changed;
    }

    private int reconcileIntegrity(List<String> failures) {
        int changed = 0;
        List<SettlementEvent> missing;
        try { missing = events.findEventsWithoutOutboxEntry(SettlementStatus.COMMITTED); }
        catch (Exception ex) { failure(failures, "missing outbox scan", ex); missing = List.of(); }
        for (SettlementEvent candidate : missing) {
            changed += isolated(failures, "missing outbox for " + candidate.getId(), () -> transaction.execute(status -> {
                // No outbox row exists to lock. The event lock serializes creators; unique FK is the final guard.
                var event = events.findLockedById(candidate.getId()).orElseThrow();
                if (event.getStatus() != SettlementStatus.COMMITTED
                        || outbox.findBySettlementId(event.getId()).isPresent()) return 0;
                UUID id = UUID.randomUUID();
                var metadata = attachments.findBySettlementEventId(event.getId()).stream()
                        .sorted(Comparator.comparing(SettlementAttachment::getId))
                        .map(a -> new SettlementAcceptedPayload.AttachmentMetadata(
                                a.getOriginalFileName(), a.getContentType(), a.getFileSizeBytes())).toList();
                var payload = new SettlementAcceptedPayload(id, "1.0", "settlement.accepted", event.getId(),
                        event.getSettlementType().name(), event.getAccountId(), event.getCurrency(),
                        event.getAmount().toPlainString(), event.getOriginalSettlementId(), event.getCreatedAt(), metadata);
                try { outbox.saveAndFlush(new SettlementOutboxEntry(id, event, mapper.writeValueAsString(payload))); }
                catch (JsonProcessingException ex) { throw new IllegalStateException("Cannot reconstruct payload", ex); }
                return 1;
            }));
        }
        List<Long> ids;
        try { ids = events.findReconciliationIds(); }
        catch (Exception ex) { failure(failures, "outbox status scan", ex); return changed; }
        for (Long id : ids) {
            changed += isolated(failures, "outbox status for " + id, () -> transaction.execute(status -> {
                var found = outbox.findLockedBySettlementId(id);
                if (found.isEmpty()) return 0;
                var event = events.findLockedById(id).orElseThrow();
                var entry = found.get();
                if (entry.getStatus() == OutboxStatus.PUBLISHED && event.getStatus() == SettlementStatus.COMMITTED) {
                    event.transitionTo(SettlementStatus.DISPATCHED);
                    events.saveAndFlush(event);
                    return 1;
                }
                if (entry.getStatus() == OutboxStatus.PENDING && event.getStatus() == SettlementStatus.DISPATCHED) {
                    entry.markPublished(OffsetDateTime.now(ZoneOffset.UTC));
                    outbox.saveAndFlush(entry);
                    return 1;
                }
                return 0;
            }));
        }
        return changed;
    }

    private int isolated(List<String> failures, String item, Supplier<Integer> action) {
        try { return action.get(); }
        catch (Exception ex) { failure(failures, item, ex); return 0; }
    }

    private void failure(List<String> failures, String item, Exception ex) {
        failures.add(item + ": " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
        log.error("Reconciliation failed for {}; retained for retry/investigation", item, ex);
    }
}
