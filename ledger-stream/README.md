# LedgerStream: Idempotent Event & Settlement Dispatcher

An independent portfolio project for recording settlement events and, in a later phase,
dispatching notifications. It does **not** move money, debit or credit accounts, calculate
balances, execute settlement, or promise end-to-end exactly-once delivery.

## Current implementation

- Java 21, Spring Boot 3.4.0, Maven 3.9.9, Spring Data JPA/Hibernate, Flyway and PostgreSQL 16.
- Positive monetary values from `0.01` to `9999999999999.99`, parsed directly from decimal
  strings into `BigDecimal`. Excess precision is rejected; `1`, `1.0` and `1.00` normalize to `1.00`.
- Event/attachment entities, unique idempotency keys, and migrations V1/V2.
- Application service for acceptance, equivalent replays, conflicts and chargeback validation.
- NIO staging with SHA-256 of the bytes written, exclusive attempt directories, mandatory
  atomic promotion and observable cleanup failures.
- Unit/filesystem tests and PostgreSQL integration tests, separated by Surefire/Failsafe.

The REST endpoints in [OpenAPI](docs/openapi.yaml) are **planned contracts**; there is no
controller yet. RabbitMQ 3.13 is available in Compose and Spring AMQP is a dependency,
but there is no publisher, consumer, outbox or crash-reconciliation worker. The current
application flow ends at `COMMITTED`.

## Acceptance and file lifecycle

1. Validate metadata and stage any attachment in a unique attempt directory. The caller
   owns and closes the input stream. Hash metadata with unambiguous, length-prefixed fields
   and include the attachment's byte digest.
2. Reuse an equivalent existing event, or reject a conflicting payload. Clean only the
   current attempt's staged file.
3. In an explicit database transaction, validate chargeback references and insert the event
   and attachment reference. Database uniqueness arbitrates concurrent inserts.
4. After confirmed commit, promote the file with `ATOMIC_MOVE` and record its permanent path
   and attachment status in a second transaction. A promotion or metadata-update failure
   leaves the accepted event intact for future reconciliation.

The service rejects calls inside an existing transaction, so an outer transaction cannot
postpone acceptance until after file promotion. Cleanup after an acceptance exception is
allowed only when rollback completion is confirmed. An unknown commit result preserves
files and logs the key/path for reconciliation. Completion callbacks classify a live
transaction's outcome; they are **not** a crash-recovery mechanism.

Permanent storage uses the same attempt UUID as staging: `<root>/<attempt-uuid>/content`.
Creating the destination directory exclusively prevents competing writers from replacing
an existing file; removing `REPLACE_EXISTING` alone would not provide that guarantee.
Storage roots must be trusted and application-owned, without external writers. If atomic
move is unsupported, promotion fails explicitly and keeps the source. Atomic rename does
not promise durability after power loss. Empty attempt markers and interrupted promotions
remain until a future reconciler can make a safe, state-aware decision; TTL alone is insufficient.

### States

| State | Meaning |
| --- | --- |
| `STAGED` | Preparation before durable acceptance |
| `COMMITTED` | Database acceptance confirmed; files and dispatch may remain pending |
| `DISPATCHED` | Future publisher confirmation and required routing verified, with attachments ready; not consumption or settlement |
| `FAILED` | Preparation definitively abandoned before acceptance |
| `COMPENSATED` | Cleanup of abandoned preparation actually completed |

Allowed transitions: `STAGED → COMMITTED`, `STAGED → FAILED`, `STAGED → COMPENSATED`,
`FAILED → COMPENSATED`, `COMMITTED → DISPATCHED`. `DISPATCHED` and `COMPENSATED` are terminal.
The application service does not yet produce dispatch or durable preparation-failure records.

Chargebacks are new positive-valued events with their own keys. They reference an existing
non-chargeback event of the same account and currency, without modifying it. There is no
cumulative refundable-balance calculation or financial eligibility certification.

### Checksum compatibility

The corrected checksum format includes a version domain separator and the actual attachment
bytes. It is incompatible with the earlier delimiter-based checksum. Existing synthetic events
keep their stored checksums and may return a conflict on retry. This change does not rewrite old
records or silently accept legacy checksums that failed to cover attachment bytes. A retained
dataset would require an explicit, validated migration before adopting this format.

## Reproduce locally

Run from `ledger-stream/`. Java 21 is required. Maven is bootstrapped by the wrapper;
Docker is additionally required for PostgreSQL integration tests.

```bash
./mvnw clean test --no-transfer-progress
./mvnw clean verify --no-transfer-progress
```

On Windows, use `mvnw.cmd` with the same arguments. `test` runs unit/filesystem tests;
`verify` also runs `*IT` tests using Testcontainers 1.20.4 and PostgreSQL 16. Integration tests
exercise real commits, concurrent equivalent requests, attachment references and chargebacks.
They must fail if Docker is unavailable, rather than report skipped integration as success.

For development infrastructure: `docker compose up -d`. This starts PostgreSQL and RabbitMQ,
not the Java application. The Compose credentials are only for local synthetic demonstrations.

## Validation and remaining work

See [the current work record](../portfolio-context/estado.md) for observed commands, results
and limitations. Historical test totals are not evidence that a fresh checkout passes.
The remote baseline `8edb762` failed in Maven bootstrap before compiling; subsequent local
changes require a successful Actions run for their own commit; consult the workflow result rather than inferring CI success from local unit tests.

The next phase remains transactional outbox, followed by resilient messaging, an idempotent
consumer, state-aware crash recovery and interview failure demonstrations. Outbox entries
must be written in the acceptance transaction; direct publication is not an interim substitute.

## Originality

This is an independent demonstrative project with original requirements and synthetic data.
It does not reproduce systems, proprietary code, data or rules of employers or clients.
