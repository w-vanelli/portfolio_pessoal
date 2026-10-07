# LedgerStream: Idempotent Event & Settlement Dispatcher

An independent portfolio project for recording settlement events and dispatching notifications.
It does **not** move money, debit or credit accounts, calculate balances, execute settlement,
or promise end-to-end exactly-once delivery.

## Architecture and resilience

The five planned resilience practices are implemented in this repository:

| Practice | Implementation |
| --- | --- |
| Transactional Outbox Pattern | Acceptance writes the event and a unique `PENDING` outbox row in one database transaction. A dispatcher publishes with correlated RabbitMQ confirms, mandatory routing and retry/backoff. |
| Idempotent Consumer (CQRS) | The consumer deduplicates deliveries and updates a separate read projection; failed deliveries can reach the DLQ. |
| Post-failure reconciliation | A reconciliation service handles orphan staging files, delayed attachment promotion and outbox integrity; an admin endpoint can trigger it. |
| Audit Trail | Status creation and real transitions are persisted with the aggregate and exposed at `GET /api/v1/settlements/{id}/audit`. |
| Chaos Engineering tests | Integration tests inject storage and broker failures and exercise recovery through the outbox and CQRS projection. |

The application uses Java 21, Spring Boot 3.4.0, Maven 3.9.9, PostgreSQL 16,
RabbitMQ 3.13, Flyway and Spring Data JPA. The [OpenAPI contract](docs/openapi.yaml)
documents ingestion, queries and operational endpoints. The integration tests use
Testcontainers with real PostgreSQL and RabbitMQ instances.

Positive monetary values range from `0.01` to `9999999999999.99`. Inputs are parsed
as decimal strings into `BigDecimal`; excess precision is rejected, and `1`, `1.0`
and `1.00` normalize to `1.00`.

These practices improve recovery and duplicate handling; they do not make delivery
exactly once or make the audit table immutable against privileged database changes.

## Acceptance and file lifecycle

1. Validate metadata and stage any attachment in a unique attempt directory. Hash
   metadata with length-prefixed fields and include the attachment's byte digest.
2. Reuse an equivalent existing event, or reject a conflicting payload. Database
   uniqueness arbitrates concurrent inserts by idempotency key.
3. In one database transaction, insert the event, attachment reference and unique
   `PENDING` outbox row. The acceptance service does not call the broker.
4. After commit, promote the file with `ATOMIC_MOVE` and update attachment status.
   Reconciliation can recover a promotion or metadata-update failure.
5. The dispatcher publishes pending outbox entries. Confirmed routing marks dispatch;
   the consumer applies idempotent updates to the CQRS read projection.

The service rejects calls inside an existing transaction so file promotion cannot
precede the acceptance commit. Cleanup after an exception requires confirmed rollback.
An unknown commit result preserves files for reconciliation. Storage roots must be
trusted and application-owned. Atomic rename does not guarantee durability after
power loss. Permanent files use the attempt UUID at `<root>/<attempt-uuid>/content`;
exclusive destination creation prevents competing writers from replacing a file.
If atomic move is unsupported, promotion fails and retains the staged source.

| State | Meaning |
| --- | --- |
| `STAGED` | Preparation before durable acceptance |
| `COMMITTED` | Database acceptance confirmed; files or dispatch may remain pending |
| `DISPATCHED` | Broker publication confirmed and required routing verified; this does not imply consumption or financial settlement |
| `FAILED` | Preparation definitively abandoned before acceptance |
| `COMPENSATED` | Cleanup of abandoned preparation completed |

Allowed transitions: `STAGED → COMMITTED`, `STAGED → FAILED`, `STAGED → COMPENSATED`,
`FAILED → COMPENSATED`, `COMMITTED → DISPATCHED`. `DISPATCHED` and `COMPENSATED`
are terminal.

Chargebacks are new positive-valued events with their own keys. They reference an
existing non-chargeback event of the same account and currency, without modifying it.
There is no cumulative refundable-balance calculation or financial eligibility check.

The checksum format includes a version domain separator and actual attachment bytes.
It is incompatible with an earlier delimiter-based checksum. Existing synthetic
events keep their stored checksums and may conflict on replay; a retained dataset
would need a validated migration.

## Run locally

Run commands from `ledger-stream/`. Docker Compose builds the application image and
starts it with PostgreSQL and RabbitMQ. The Compose credentials are for local synthetic
demonstrations only.

```bash
docker compose up --build -d
docker compose ps
```

The API listens on `http://localhost:8080`; health is available at
`http://localhost:8080/actuator/health`. RabbitMQ management is at
`http://localhost:15672`. Compose waits for PostgreSQL and RabbitMQ health checks
before starting the app. Database, broker and attachment storage use named volumes.
To stop the stack without removing those volumes, run `docker compose down`.

For a host-based run, Java 21 is required. Start only the infrastructure with
`docker compose up -d postgres rabbitmq`, then run `./mvnw spring-boot:run` (or
`mvnw.cmd` on Windows). The default `application.yml` points to localhost;
Compose overrides those hosts with service names.

## Validation and remaining work

```bash
./mvnw clean test --no-transfer-progress
./mvnw clean verify --no-transfer-progress
```

On Windows use `mvnw.cmd`. `test` runs unit and slice tests; `verify` also runs
`*IT` tests with Testcontainers. Docker must be available for those integration tests.
See [the work record](../portfolio-context/estado.md) for observed results and
limitations. Historical totals do not prove that a fresh checkout passes.

## Originality

This is an independent demonstrative project with original requirements and synthetic
data. It does not reproduce systems, proprietary code, data or rules of employers
or clients.
