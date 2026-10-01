package com.wvanelli.ledgerstream.application;

import java.time.Instant;
import java.util.List;

/** Counts include only completed item transactions; failures require retry or investigation. */
public record ReconciliationReport(Instant startedAt, Instant completedAt,
        int orphanDirectoriesRemoved, int attachmentsReconciled, int outboxRepairs,
        List<String> failures) {
    public ReconciliationReport { failures = List.copyOf(failures); }
}
