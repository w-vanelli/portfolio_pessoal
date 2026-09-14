# 🌊 LedgerStream: Idempotent Event & Settlement Dispatcher

[![Java 21](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot 3.4](https://img.shields.io/badge/Spring%20Boot-3.4-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-blue.svg)](https://www.postgresql.org/)
[![RabbitMQ](https://img.shields.io/badge/RabbitMQ-3.13-coral.svg)](https://www.rabbitmq.com/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

> A mission-critical financial settlement ingestion and dispatching engine designed to solve distributed consistency challenges in B2B SaaS platforms: **atomic idempotency barriers**, **two-phase transactional file staging with automatic compensation**, and **fault-tolerant asynchronous messaging**.

---

## 🎯 The Engineering Challenge

In distributed financial ecosystems (such as billing webhooks, payment gateways, and high-frequency B2B invoicing), network retries and concurrent client bursts regularly induce race conditions and data corruption:

1. **Duplicate Event Hazards:** Naive `SELECT ... INSERT` patterns fail under simultaneous concurrent requests, causing double-crediting or duplicate ledger entries.
2. **Physical I/O vs. Relational Mismatch:** Uploading physical receipts or proof-of-payment documents directly into permanent storage during an open SQL transaction risks leaving **orphan storage files** if the database transaction rolls back. Conversely, committing the database record before persisting the file risks missing mandatory attachments upon storage failure.
3. **Downstream Unavailability:** Partner APIs and message brokers experience transient network partitions. Dispatching operations must withstand latency spikes without blocking HTTP worker threads.

---

## 🏛️ Architecture & Solution Design

```mermaid
sequenceDiagram
    autonumber
    actor Client as B2B Client / Webhook
    participant API as SettlementController
    participant Engine as SettlementService
    participant Storage as StagingStorageService (NIO.2)
    participant DB as PostgreSQL (HikariCP)
    participant Broker as RabbitMQ (AMQP Exchange)

    Client->>API: POST /api/v1/settlements (X-Idempotency-Key, Data, File)
    API->>Engine: processSettlement(key, payload, file)
    Engine->>Storage: saveToStaging(file)
    Note over Storage: Written to /storage/staging/*.tmp
    Engine->>DB: BEGIN TRANSACTION
    Engine->>DB: INSERT INTO settlement_events (UNIQUE idempotency_key)
    alt Idempotency Conflict (Concurrent Duplicate)
        DB-->>Engine: UniqueConstraintViolationException
        Engine->>Storage: compensate(delete staging file)
        Engine-->>API: 200 OK / 409 Conflict (Replay cached state)
        API-->>Client: Return existing settlement
    else Successful Insert
        Engine->>DB: COMMIT TRANSACTION
        Engine->>Storage: promoteToPermanent(file)
        Note over Storage: Atomic rename to /storage/permanent/*
        Engine->>Broker: publishEvent(settlement.created)
        Broker-->>Engine: ACK
        Engine-->>API: 201 Created (Settlement Details)
        API-->>Client: 201 Created
    end
```

### Key Architectural Pillars
- **Strict Idempotency Barrier:** Enforced directly in PostgreSQL via `UNIQUE (idempotency_key)` and SHA-256 payload checksum comparison. Subsequent duplicate requests safely return the existing resource without executing side-effects.
- **Two-Phase Compensating Storage:** Physical attachments are staged in temporary storage (`java.nio.file`) isolated from the transactional lifecycle. Only upon a verified database commit is the file atomically promoted to permanent storage. If the transaction fails, a registered compensation routine guarantees immediate deletion of temporary artifacts.
- **Asynchronous AMQP Dispatcher:** Offloads event notification to RabbitMQ with configured Dead-Letter Exchanges (DLX), exponential backoff retries, and Circuit Breaker isolation via Resilience4j.
- **Contract-First Documentation:** Complete REST specification modeled in [docs/openapi.yaml](docs/openapi.yaml).

---

## 🚀 Quick Start (Local Reproduction)

### Prerequisites
- **Docker & Docker Compose** (tested on Docker Engine 24+)
- **Java 21** & **Maven 3.9+** (optional if using containerized execution)

### 1. Spin up Infrastructure (PostgreSQL 16 & RabbitMQ)
```bash
docker compose up -d
```

Verify that services are running and healthy:
```bash
docker compose ps
```
- **PostgreSQL:** `localhost:5432` (DB: `ledgerstream`, User: `ledger`)
- **RabbitMQ Management UI:** [http://localhost:15672](http://localhost:15672) (User: `guest` / `guest`)

### 2. Run the Application
```bash
./mvnw spring-boot:run
```
Or via Maven wrapper on Windows PowerShell:
```powershell
.\mvnw.cmd spring-boot:run
```

The application starts on `http://localhost:8080`.

---

## 🧪 Testing Strategy

The test suite prioritizes high-risk failure scenarios:
- **Multithreaded Concurrency Tests:** Simulates 50 simultaneous threads issuing identical requests to verify zero duplicate records and thread confinement.
- **Rollback Compensation Verification:** Forces artificial database constraints to prove physical staging files are deleted without leaving storage orphans.
- **Full-Stack Integration:** Uses **Testcontainers** to dynamically spin up isolated PostgreSQL and RabbitMQ instances during `mvn test`.

Run tests locally:
```bash
./mvnw test
```

---

## 📋 API Specification Summary

| Method | Endpoint | Description | Idempotent |
| :--- | :--- | :--- | :---: |
| `POST` | `/api/v1/settlements` | Ingest new settlement (Multipart: JSON + file) | ✅ (`X-Idempotency-Key`) |
| `GET` | `/api/v1/settlements/{id}` | Fetch settlement by internal ID | ✅ |
| `GET` | `/api/v1/settlements/idempotency/{key}` | Fetch settlement by Idempotency Key | ✅ |
| `GET` | `/api/v1/settlements/{id}/audit` | Retrieve immutable audit history | ✅ |

Refer to [`docs/openapi.yaml`](docs/openapi.yaml) for full request/response schemas and examples.

---

## 🛡️ Originality & Engineering Disclosure

*This is an independent open-source project with self-contained, original requirements designed to showcase advanced software engineering patterns (concurrency safety, transaction compensation, event-driven resilience, and automated verification). It does not reproduce business rules, proprietary code, or data models from any current or past employer.*
