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
  - Originally the dispatcher used a single-instance scheduler without distributed locking. Increment 7 adds producer row locks shared with reconciliation; duplicate delivery remains possible after unknown broker/commit outcomes.
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

1. State-aware reconciliation is implemented in Increment 7 below; validate its recovery scenarios with the full integration suite.

### Increment 5 - Idempotent Consumer and Projections (Verified)

Implemented and verified: A RabbitMQ consumer that processes settlement events idempotently to build an operational projection.
- **Idempotency Strategy**: Uses a dedicated `consumer_idempotency` table combined with a transactional save. Unique constraint `message_id` natively prevents race conditions on parallel consumption. Transactions atomically commit both the idempotency marker and the projection; if the projection fails, idempotency is also rolled back, allowing safe redelivery. (Note: Initial designs referenced a "lock table", but optimistic primary-key enforcement was used directly for better reliability).
- **Operational Projection**: Creates a `settlement_projection` record (via migration `V6`) representing the accepted settlement. This is strictly an intentional *scope decision* for simple operational queries (by ID and Account ID), and not a technical debt. It does **not** implement financial execution, balance calculation (`account_balance_shadow`), debits, credits, or value accumulation. Replay functionality remains out of scope.
- **Contract Strictness**: The consumer (`SettlementEventConsumer`) strictly validates the schema `version=1.0`, `eventType`, payload completeness, payload logic (`messageId` matches AMQP header), and ensures strictly positive monetary amounts. Malformed messages are immediately rejected (no requeue) via `AmqpRejectAndDontRequeueException` to `ledger.settlement.events.dlq`.
- **Consumer Resilience**: Employs `ackMode = "AUTO"`. Transient infrastructure failures trigger up to 3 local retries before exhausting and falling into the DLQ. The `EndToEndSettlementIT` validates the complete path from `SettlementApplicationService` to the `settlement_projection` without `auto-startup` interference from other tests.

### Increment 6 - Operational Projection Query REST Endpoints (Verified)

Implemented and verified: REST query API exposing the operational read-model (`settlement_projection`) for operators, clients, and downstream systems.
- **REST Endpoints**:
  - `GET /api/v1/settlements/{id}`: Look up a projected settlement by its numeric internal settlement ID. Returns `200 OK` with `SettlementProjectionResponse` or `404 Not Found` with standardized `ErrorResponse` if the settlement has not yet been processed by the consumer or does not exist.
  - `GET /api/v1/settlements?accountId=...`: Query all projected settlements for a specific merchant/account, ordered by acceptance timestamp descending (`accepted_at DESC`). Returns `200 OK` with a JSON list (empty list `[]` if none found) or `400 Bad Request` if `accountId` is missing/blank.
- **Schema & Indexing (Migration V7)**:
  - Adds unique index `CREATE UNIQUE INDEX idx_settlement_projection_settlement_id ON settlement_projection(settlement_id);` to ensure O(1) index seek by numeric settlement ID and enforce single-projection database integrity.
- **Global Error Contract**:
  - `GlobalExceptionHandler` (`@RestControllerAdvice`) maps not-found (`ResourceNotFoundException`), illegal arguments (`IllegalArgumentException`), parameter type mismatches, and missing parameters directly to the OpenAPI-compliant `ErrorResponse` JSON structure.
- **CQRS Read-Model Scope Boundary**:
  - The query endpoints read strictly from `settlement_projection` (the consumer read model), preserving CQRS decoupling. They do not query or mutate the producer tables (`settlement_events`, `settlement_outbox`).
- **Validation Evidence**:
  - Unit tests for service and slice tests for controller with MockMvc (`SettlementProjectionQueryServiceTest`, `SettlementQueryControllerTest`).
  - Integration tests with real PostgreSQL Testcontainer exercising migrations V1 to V7 (`SettlementQueryControllerIT`).
  - End-to-end integration (`EndToEndSettlementIT`) validating full flow from settlement registration through outbox dispatch and consumer projection to query service retrieval.

No exactly-once end-to-end guarantee. Dispatch confirmation is not consumer completion or
financial settlement. In-memory callbacks cannot recover state after a crash.


## 4. Interview demonstrations

Preserved scope: show concurrent requests, changed payload conflicts, failed preparation,
unknown commit outcomes, file promotion failure, broker failure and recovery. Demonstrate only
behaviors exercised by tests; mark future broker/crash demonstrations as planned.


### Increment 7 - Reconciliation and failure recovery

`SettlementReconciliationService` exposes `reconcileAll()`,
`reconcileOrphanStagingFiles(Duration)`, `reconcileStagedAttachments()` and
`reconcileOutboxIntegrity()`. The scheduled entry point runs the same recovery code.
`ReconciliationReport` records start/end time, completed repair counts and an immutable
list of failures. Item errors are logged with their identifier and exception; a scan
failure does not prevent the other phases from running. Missing bytes remain an
operational incident, with the original attachment reference preserved.

Configuration (defaults, no configuration file change required):

| Property | Default | Meaning |
| --- | --- | --- |
| `ledgerstream.reconciliation.enabled` | `true` | Enables scheduled recovery; manual calls remain available |
| `ledgerstream.reconciliation.poll-interval-ms` | `60000` | Fixed delay after the previous scheduled run completes |
| `ledgerstream.reconciliation.initial-delay-ms` | `10000` | Delay before the first scheduled run |
| `ledgerstream.reconciliation.orphan-grace-period-seconds` | `900` | Nonnegative minimum age before orphan cleanup |

Recovery decisions:

- Owned staging attempts use exactly `<staging-root>/<lowercase-UUID>/content`.
  The storage service rejects traversal, symlinks in roots/ancestors/attempts/content,
  wrong path shapes and nonregular content. It validates roots again on each operation.
  Existing `./storage/...` root configuration is supported and roots are canonicalized
  to preserve the string format used by persisted staging tickets.
- Only empty directories or directories containing a single regular `content` file
  can be cleaned. Every child is checked before any deletion; cleanup never recurses.
  Foreign names and symlink directories are excluded from enumeration. Unexpected
  children in owned attempts fail independently and remain available for investigation.
- Orphan age is conservative: both creation and last-modified times of directory and
  content must precede the cutoff. Age is rechecked after the database lookup. A
  persisted `storage_path` prevents deletion, including an empty referenced directory.
  A database lookup failure never means absence. Unreferenced old empty directories
  are removed as well. Future timestamps preserve the attempt until time catches up.
- For `STAGED` attachments whose events are `COMMITTED` or `DISPATCHED`, an existing
  permanent file at the same UUID repairs the metadata. Otherwise staging content is
  promoted with mandatory `ATOMIC_MOVE`. An empty permanent directory left by a crash
  before the move is reusable. A stable `.promotion.lock` file in the permanent root,
  an OS file lock and a JVM monitor serialize reservation reuse and target checks
  among cooperating application instances. The lock file is never removed: replacing
  it could allow two processes to lock different inodes. An existing target is never
  overwritten; a nonempty unexpected reservation is rejected. There is no nonatomic
  fallback. Empty staging markers are removed after promotion.
- `FAILED`/`COMPENSATED` events cause staging compensation and attachment `PURGED`.
  Compensation never deletes permanent files. Content missing at both locations
  leaves `STAGED` metadata unchanged and produces an operational failure. If both
  locations contain content, permanent metadata is recovered without deleting the
  staging duplicate in the attachment transaction; ordinary orphan grace still applies.
- Attachment metadata and readiness-based outbox unblocking commit in the same item
  transaction. Once every attachment is `PERMANENT`, only a `PENDING` outbox is made
  immediately available, without consuming attempts or clearing failure history.
  `FAILED` and `PUBLISHED` entries are not resurrected. A database rollback after the
  file move leaves a discoverable permanent UUID; the next cycle repairs metadata.
- A `COMMITTED` event without an outbox gets one `PENDING` entry reconstructed from
  persisted event/attachment metadata. The existing schema `1.0`, event type,
  `created_at` acceptance timestamp, monetary string and chargeback reference are
  preserved. The newly generated outbox UUID is also the JSON `messageId`. Attachment
  metadata is ordered by attachment ID. Existing entries and payloads are not rebuilt.
  The event lock and existing unique settlement constraint prevent duplicate entries.
- `PUBLISHED` outbox plus `COMMITTED` event repairs the event to `DISPATCHED`;
  `DISPATCHED` event plus `PENDING` outbox repairs the entry to `PUBLISHED`.
  Identity, payload, retry history and available time remain intact. A synthesized
  `published_at` in the latter case records recovery time, not the unknown broker ACK
  timestamp. A terminal `FAILED` entry is left for explicit operator investigation.

Transactions and concurrency:

Each repair uses `REQUIRES_NEW` and reloads state after acquiring locks. Reconciliation
and dispatcher acquire producer row locks in the same order: outbox, then event.
Normal post-commit metadata persistence also locks the event. Missing-outbox creation
locks the event and rechecks absence; it does not lock an existing outbox after the
event. PostgreSQL locks protect concurrent dispatcher outcomes from stale recovery
writes. Filesystem changes are not rolled back by the database; UUID-based recovery
makes this partial-failure window retryable. Public recovery can be called concurrently,
but this is still at-least-once publishing, not exactly-once delivery.

Operational limits:

- Roots must remain application-owned on a trusted filesystem with no external writers,
  stable paths and compatible atomic-move/file-lock semantics. Path validation is not
  protection against an adversary replacing directories between syscalls. All cooperating
  processes must run the updated storage protocol; mixed old/new writer versions are
  unsupported during empty-reservation recovery. Atomic rename is not an fsync or
  power-loss durability guarantee. Shared storage across hosts requires equivalent
  filesystem guarantees and the same canonical paths.
- The in-memory active-write set protects copies in the same JVM. It does not represent
  a durable acceptance lease or protect a paused copy in another process. The interval
  after staging returns and before acceptance commits relies on the grace period and
  conservative file/directory timestamps. An uncommitted acceptance lasting longer
  than the grace period can race cleanup: a database absence check cannot lock a row
  that does not yet exist. Keep grace above maximum upload/acceptance duration; disable
  scheduled recovery during exceptional ingestion pauses, or add a durable lease
  protocol before supporting unbounded acceptance times. Zero grace is for explicit
  tests/controlled recovery and removes that cushion. Clock skew may delay cleanup or
  weaken the age assumption.
- Scans currently load candidate lists into memory; they are appropriate for this
  demonstration dataset. A large retained history needs bounded/keyset scans and
  operational metrics. Errors retry on subsequent cycles; there is no automatic
  operator replay of quarantined outbox entries. Permanent orphan deletion, checksum
  revalidation, consumer replay and financial execution remain outside this increment.
- `settlement_projection`, `consumer_idempotency`, their repositories, migrations and
  the existing event/API contracts are unchanged.

Validation coverage:

`SettlementReconciliationServiceTest` covers orphan content/empty directories, grace
and creation timestamps, database absence versus failure, isolated I/O failures and
retry, both partial-promotion windows, compensation, missing bytes, status revalidation,
readiness gating, terminal outbox preservation, payload reconstruction, status repair,
idempotency, scan failures and scheduler disablement. Storage tests cover traversal,
source/destination/root/ancestor symlinks, root replacement, unexpected children,
active local copies, empty reservations and concurrent promotion without overwrite.

`SettlementReconciliationServiceIT extends AbstractIntegrationTest` uses the real
PostgreSQL Testcontainer and temporary filesystem. It exercises SQL rollback after
an actual file move, recovery of both promotion windows, real orphan lookup,
compensation/missing references, concurrent reconstruction/promotion, status repair
and a dispatcher/reconciler race in which a committed dispatcher failure must survive.
Failures are injected at real boundaries; these tests do not claim to terminate a
process or prove power-loss durability. Test profiles disable automatic reconciliation
and postpone automatic dispatch; production scheduling defaults remain enabled.

---

### Observabilidade, Métricas Operacionais e Endpoint Administrativo

Para garantir visibilidade em tempo real sobre a saúde do processo assíncrono de reconciliação e da esteira outbox-broker, o sistema foi instrumentado com **Micrometer** e exposto via endpoints administrativos:

#### 1. Métricas Micrometer (`io.micrometer.core.instrument.MeterRegistry`)

- **Reconciliação (`SettlementReconciliationService`):**
  - `ledgerstream.reconciliation.runs` (Counter): Total de execuções de reconciliação disparadas (agendadas ou sob demanda).
  - `ledgerstream.reconciliation.duration` (Timer): Tempo de execução de cada ciclo de reconciliação.
  - `ledgerstream.reconciliation.orphans.cleaned` (Counter): Total acumulado de diretórios de staging órfãos expurgados com sucesso.
  - `ledgerstream.reconciliation.attachments.recovered` (Counter): Total acumulado de anexos STAGED regularizados para PERMANENT ou compensados para PURGED.
  - `ledgerstream.reconciliation.outbox.reconstructed` (Counter): Total acumulado de registros de outbox reconstituídos a partir de eventos COMMITTED órfãos.
  - `ledgerstream.reconciliation.failures` (Counter): Total acumulado de falhas isoladas de I/O ou banco tratadas sem abortar o ciclo.
  - **Gauges de Última Execução:**
    - `ledgerstream.reconciliation.last.orphans.cleaned`
    - `ledgerstream.reconciliation.last.attachments.recovered`
    - `ledgerstream.reconciliation.last.outbox.repairs`
    - `ledgerstream.reconciliation.last.failures`
    - `ledgerstream.reconciliation.last.completed.at`

- **Dispatcher Outbox (`OutboxDispatcherService`):**
  - `ledgerstream.dispatcher.attempts` (Counter): Tentativas de processamento de entradas da outbox.
  - `ledgerstream.dispatcher.published` (Counter): Mensagens efetivamente confirmadas pelo broker (ACK) e com status `PUBLISHED`/`DISPATCHED` persistido.
  - `ledgerstream.dispatcher.failed` (Counter): Mensagens que esgotaram a cota de retentativas (`max-attempts`) e foram marcadas como `FAILED`.
  - `ledgerstream.dispatcher.deferred` (Counter): Mensagens reagendadas (adiadas) sem queimar retentativas (ex.: anexo ainda não promovido, timeouts de confirm ou retentativas com backoff).
  - `ledgerstream.dispatcher.duration` (Timer): Duração da publicação e confirmação por lote/mensagem.

#### 2. Endpoint Operacional Administrativo

- `POST /api/v1/admin/reconcile`:
  - Dispara um ciclo imediato e síncrono de `reconcileAll()`.
  - Retorna `200 OK` com o payload canônico `ReconciliationReport` em formato JSON, detalhando timestamps de início/fim, contadores de itens regularizados e quaisquer mensagens de erro isoladas.
  - Testado via `AdminReconciliationControllerTest` (slice web MockMvc) e `AdminReconciliationControllerIT` (integração ponta a ponta com PostgreSQL Testcontainer validando incremento no `MeterRegistry`).

---

### Increment 10 — Endpoint de Ingestão de Liquidação (POST /api/v1/settlements)

Implementado e verificado: Endpoint REST de ingestão com suporte a `multipart/form-data` e garantia estrita de idempotência por payload.
- **Contrato e Roteamento**:
  - `POST /api/v1/settlements`: Recebe cabeçalho obrigatório `X-Idempotency-Key` (UUID), `metadata` (JSON mapeado em `SettlementIngestRequest`) e anexo opcional `attachment` (`MultipartFile`).
  - Distinção semântica entre nova aceitação durável (`201 Created` com header `Location`) e reprocessamento idempotente (`200 OK`).
  - O resultado de persistência retorna encapsulado em `SettlementRegistrationResult` via `SettlementApplicationService.registerSettlement()`.
- **Tratamento de Exceções (`GlobalExceptionHandler`)**:
  - Mapeia `IdempotencyConflictException` para `409 Conflict`.
  - Mapeia `InvalidChargebackException` para `400 Bad Request`.
  - Mapeia falhas estruturais HTTP (ausência de cabeçalho obrigatório, ausência de parte multipart, corpo ilegível ou validação) para `400 Bad Request`.
- **Validação e Testes**:
  - `SettlementIngestionControllerTest` (slice MockMvc) cobrindo criação (201), replay idempotente (200), payload conflict (409), chargeback inválido (400) e cabeçalhos ausentes (400).
  - `SettlementIngestionControllerIT` (integração com Testcontainers PostgreSQL) cobrindo o fluxo completo de ingestão e isolamento de storage em `${java.io.tmpdir}`.

### Increment 12 — Settlement audit trail

`SettlementEvent` owns `SettlementAuditLog` entries. Construction records `null -> STAGED`;
each valid status change appends an entry with the previous/new status and a UTC timestamp.
The existing transition matrix and producer row locks remain in force. Same-state calls,
invalid transitions and null targets leave both status and history unchanged. Cascade persistence
commits status and history in the same transaction, including rollback after a flush.

`transitionTo(status)` remains supported. `transitionTo(status, eventDetails)` accepts optional
safe operational context; callers must not include secrets, request payloads or attachment data.
Automatic entries use null details. Audit records describe producer lifecycle changes, not
every broker attempt, attachment change, consumer completion or financial execution.

V1 already created `settlement_audit_log`. V8 renames its FK column to `settlement_event_id`,
allows absent details and replaces the single-column index with `(settlement_event_id, created_at, id)`.
Existing rows, IDs, timestamps, the identity sequence and the cascading FK are preserved.
No old migration changes and no historical entries are synthesized. A legacy settlement's next
transition records only its actual previous/new status.

`GET /api/v1/settlements/{id}/audit` returns `AuditLogEntry` DTOs ordered by timestamp, then ID.
It reads producer history independently of the CQRS projection. Mapping occurs in a read-only
transaction, so `open-in-view: false` remains supported. IDs must be positive int64 values (400);
missing settlements and settlements without history return the standard 404 error contract.

The aggregate exposes an unmodifiable, ordered list and entries have no public mutation methods.
This is not a tamper-proof archive: aggregate/database deletes still cascade, and privileged SQL
can alter data. Retention policies, pagination and additional access controls are outside this
increment; the endpoint inherits the application's existing security configuration.

Validation: `SettlementEventTest` covers the full transition matrix, details and no-op/rejection
semantics; `SettlementAuditControllerTest` covers the JSON/error contract. `SettlementAuditIT`
uses PostgreSQL Testcontainers for REST ingestion/replay, isolation, cascade persistence,
rollback after flush and audit insertion failure, tied timestamp ordering, legacy records and
a V7-to-V8 upgrade preserving existing history. Run the complete suite with `./mvnw verify`
(`.\mvnw.cmd verify` on Windows).

Local validation on 2026-10-07: 145 unit/slice tests and 58 integration tests passed,
with no failures, errors or skips. The default Testcontainers connection returned HTTP 400
on this Windows Docker Desktop environment; verification used the active Docker endpoint
and an explicit client API version, only for the test process (no daemon/security changes):

```powershell
$env:DOCKER_HOST = docker context inspect --format '{{.Endpoints.docker.Host}}'
.\mvnw.cmd verify '-Dapi.version=1.44'
```
