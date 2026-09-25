# Improvement Integration Plan

## 1. Foundation and contracts — Week 1

Preserved: Java 21/Spring Boot, Maven Wrapper, PostgreSQL schema and OpenAPI contract.
The OpenAPI endpoints remain planned; a specification is not evidence of a running REST API.

## 2. Domain, persistence and storage — Week 2

### Increment 1 — Domain and repositories

Implemented: `MonetaryAmount`, event/attachment entities, enums and Spring Data repositories.
Amounts are positive decimal strings from `0.01` through `9999999999999.99`, converted directly
to `BigDecimal`, with no excess precision or silent rounding. Monetary normalization is only
one component of full-payload idempotency.

### Increment 2 — Monetary rules, storage and schema

V2 adds `CHECK (amount > 0)` and the original-settlement reference. The remote documentation
reported an NIO storage implementation and its tests, but neither was tracked at baseline
`8edb762`: the root ignore rule `**/storage/` excluded Java packages as well as runtime files.

Local correction on 2026-09-21 reconstructs the missing storage package and narrows the ignore
rule to runtime paths. Each attempt owns a UUID directory. Stage computes the digest of bytes
written without closing the caller's stream. Promotion reserves a destination directory and
requires `ATOMIC_MOVE`, without a non-atomic fallback. Reservation avoids relying on the
platform-dependent behavior of atomic move when a destination exists. Cleanup errors propagate
from storage and are logged by the application service.

Storage assumes trusted application-owned roots. Atomic rename does not guarantee durability
through power loss. Empty reservations, interrupted operations and orphan attempts require a
future reconciler using persistent state and references, not TTL alone.

### Increment 3 — Application service and transactions

The service already existed remotely; this continuation repairs its guarantees rather than
reimplementing a supposedly absent increment.

- Explicit `TransactionTemplate` owns acceptance. Calls inside an ambient transaction are
  rejected before staging, avoiding file promotion before an outer caller commits.
- Chargeback validation and event/attachment insertion share the acceptance transaction.
- Unique database keys arbitrate concurrency; only a completed rollback permits cleanup
  and duplicate recovery. Unrelated integrity violations retain the original failure.
- Commit exceptions without confirmed rollback preserve staging for reconciliation.
- File promotion happens after confirmed commit. A second transaction records permanent
  attachment metadata. Failure in either post-commit step preserves the event and file.
- Length-prefixed checksum fields avoid delimiter collisions and include the attachment's
  actual content digest and original-settlement reference. Null and empty description are
  distinct; currency is required in uppercase. The earlier checksum format is incompatible;
  existing events are not rewritten or silently accepted under weaker legacy semantics.
- The current flow ends at `COMMITTED`. There is no provisional direct broker publication.

## Validation evidence

The baseline GitHub Actions run
[35452038814](https://github.com/w-vanelli/portfolio_pessoal/actions/runs/35452038814)
failed before compilation because the Unix wrapper moved an extracted directory into itself.
Both wrappers were corrected locally; Unix bootstrap was exercised from an empty Maven cache.
Windows execution still requires verification on Windows.

Local Java 21 unit/filesystem results and the full-build limitation are recorded in
[estado.md](../../portfolio-context/estado.md). Historical totals of 27, 49 and 59 tests describe
prior reports, not reproducible validation of the current remote tree.

The integration suite includes PostgreSQL schema/constraint tests and service tests for real
commits, concurrent equivalent requests, attachment metadata and chargebacks. Testcontainers
uses one explicitly started PostgreSQL instance across the cached Spring test contexts. Unit
transaction tests use Spring completion callbacks with simulated outcomes; they do not replace
real PostgreSQL integration or prove recovery after process termination.

### Increment 4 — Transactional Outbox and Dispatcher

Implemented locally: `SettlementApplicationService` generates an outbox UUID upfront, embedding it into an immutable JSON payload and storing both in `settlement_outbox` inside the acceptance transaction. This outbox ID provides a stable logical event identity across retries, enabling consumer deduplication.

Migration `V4` adds the mandatory payload column and sets it for new entries. A new migration `V5` explicitly quarantines pre-existing legacy `V3` outbox records. Entries lacking a real payload are marked `FAILED` with a sentinel `_quarantine` JSON metadata payload rather than fabricating invalid fictitious events. This prevents publication while preserving the outbox row, settlement, and any associated files for manual review. No administrative replay or legacy data recovery is implemented in this increment.

The scheduled `OutboxDispatcherService` processes `PENDING` entries sequentially:
- **Attachment Gating**: Publication is deferred without consuming attempts until all attachments reach `PERMANENT` storage.
- **Correlated Confirms**: Uses Spring AMQP correlated publisher confirms and returns (`mandatory: true`).
- **Atomicity**: Success (broker ACK without Return) marks the outbox `PUBLISHED` and the event `DISPATCHED` in a local `REQUIRES_NEW` transaction. Failure to save to the DB after an ACK triggers a transaction rollback, leaving the record `PENDING` and allowing recovery via duplicate dispatch.
- **Resilience**: NACKs and Returns trigger exponential backoff with jitter up to a terminal `FAILED` state (which stops retries but preserves the event and files).
- **Timeout as Unknown**: Wait timeouts limit the confirm check to five seconds. A timeout indicates an attempt was made but the outcome is unknown. This *consumes* a retry attempt and reschedules the entry. If the limit is reached, it goes to `FAILED`. Duplicate delivery may occur on the next attempt.
- **Deliberate Limitations**: 
  - The dispatcher runs on a single-instance scheduler; it lacks distributed locking for concurrent replicas.
  - At-least-once delivery semantics imply the possibility of duplicate events being published (e.g. on confirm timeout or DB rollback after ACK).
  - No consumer application or idempotency logic is implemented in this increment.

### Validation evidence

The integration suite uses Testcontainers for isolated RabbitMQ and PostgreSQL contexts. `mvnw.cmd clean verify` on Windows completed successfully with:
- 83 unit/filesystem tests passed via Surefire.
- 23 integration tests passed via Failsafe (including `*IT`).
- No failures, errors, or skipped tests.

Integration tests explicitly prove transactional rollback after broker ACK, concurrent equivalent requests, and exact delivery to RabbitMQ queues with stable AMQP headers and payloads.

## 3. Messaging and resilience — next macro phase

After the current service passes `clean verify` with Docker:

1. Implement an idempotent consumer and failure handling.
2. Implement state-aware reconciliation for unknown commits and interrupted file operations.

No exactly-once end-to-end guarantee. Dispatch confirmation is not consumer completion or
financial settlement. In-memory callbacks cannot recover state after a crash.

## 4. Interview demonstrations

Preserved scope: show concurrent requests, changed payload conflicts, failed preparation,
unknown commit outcomes, file promotion failure, broker failure and recovery. Demonstrate only
behaviors exercised by tests; mark future broker/crash demonstrations as planned.
