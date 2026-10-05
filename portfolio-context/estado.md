# Estado do portfólio

Atualizado em 05/10/2026. Fase: Semana 4 — consolidação dos Incrementos 4 a 10 (Outbox Dispatcher, Consumer CQRS, Reconciliação Pós-Falha, Observabilidade/Admin, Engenharia de Caos, Ingestão REST e CI Green).

## Base verificada e ambiente

- GitHub conectado: `w-vanelli/portfolio_pessoal`, branch `main`, commit mais recente `24a7023`.
- Ambiente de desenvolvimento: Windows (PowerShell) e runner GitHub Actions (Ubuntu 22.04).
- Stack conferida: Java 21 (Temurin), Spring Boot 3.4.0, Maven 3.9.9, Spring Data JPA/Hibernate, Flyway, PostgreSQL 16 (Testcontainers), RabbitMQ 3.13 (Testcontainers), Micrometer Core.
- Suíte de testes: 100% verde (124 testes unitários e de slice + 45 testes de integração com Testcontainers PostgreSQL e RabbitMQ; total 169 testes).

## Evolução Implementada (Incrementos 1 a 9)

1. **Incremento 1 & 2 — Fundação, Domínio e Schema:**
   - Value object `MonetaryAmount` (escala fixa 2 casas, arredondamento `HALF_EVEN`, aritmética segura).
   - Entidades de domínio (`SettlementEvent`, `SettlementAttachment`) com matriz de transição de status (`STAGED`, `COMMITTED`, `DISPATCHED`, `COMPENSATED`, `FAILED`, `PURGED`).
   - Migrations Flyway V1 e V2 com constraints de integridade e idempotência.
2. **Incremento 3 — Storage Staging Seguro e Checksum:**
   - `StagingStorageServiceImpl`: Isolamento de storage em duas fases (`staging` e `permanent`). Prevenção de directory traversal e symlink injection.
   - `SettlementAcceptanceService`: Transação de aceitação (`TransactionTemplate`) desacoplada de I/O em disco; promoção via `ATOMIC_MOVE` e digest SHA-256 canônico com delimitadores explícitos.
3. **Incremento 4 — Outbox Transacional e Dispatcher Resiliente:**
   - Migration V3: Tabela `settlement_outbox` com lock otimista (`available_at`, `status`, `retry_count`, `last_error`).
   - `OutboxDispatcherService`: Polling resiliente com Publisher Confirms correlacionados no RabbitMQ (`CorrelationData`), backoff exponencial com jitter e quarentena de falhas persistentes.
4. **Incremento 5 & 6 — Consumer Idempotente e Projeção CQRS:**
   - Migration V4 e V5: Tabelas `settlement_projection` e `consumer_idempotency` pertencentes exclusivamente ao modelo de leitura (CQRS).
   - `SettlementEventConsumer`: Consumo concorrente com dedup idempotente e DLQ (`settlement.dlq`) com routing key `settlement.events.dlq`.
   - `SettlementProjectionController`: Endpoints REST de consulta operacional (`GET /api/v1/settlements/{id}`, `GET /api/v1/settlements/by-idempotency/{key}`, `GET /api/v1/settlements`).
5. **Incremento 7 — Reconciliação e Recuperação de Falhas:**
   - `SettlementReconciliationService`:
     - `reconcileOrphanStagingFiles`: Varredura segura com grace period configurável (`orphan-grace-period-seconds`, padrão 900s), conferindo se diretórios em `staging` têm registro no banco; remoção segura de órfãos.
     - `reconcileStagedAttachments`: Promoção retardada de anexos que ficaram em `STAGED` após commit ou compensação para `PURGED` em eventos com falha.
     - `reconcileOutboxIntegrity`: Reconstituição de outbox órfã com payload canônico e alinhamento de status.
   - Lock cooperativo `.promotion.lock` e atomicidade de diretórios.
6. **Incremento 8 — Observabilidade e Gestão Administrativa:**
   - Instrumentação Micrometer com contadores e timers (`settlement.reconciliation.*`, `settlement.outbox.*`) no `MeterRegistry`.
   - `AdminReconciliationController`: Endpoint `POST /api/v1/admin/reconcile` para disparo sob demanda retornando `ReconciliationReport` estruturado.
   - Documentação OpenAPI (`docs/openapi.yaml`) e guia de arquitetura (`docs/improvement-integration.md`) sincronizados.
7. **Correção de Portabilidade CI (Linux / Windows):**
   - Resolução de caminhos no perfil `messaging-integration-test`: substituição de caminhos fixos de raiz por `${java.io.tmpdir}/ledgerstream-messaging-test/...`, corrigindo falhas de permissão no runner Ubuntu.
8. **Incremento 9 — Simulação de Falhas & Engenharia de Caos:**
   - `StorageAndBrokerFailureChaosIT`: Teste de integração ponta a ponta simulando falha transiente no broker RabbitMQ (`AmqpException`) durante a confirmação de outbox.
   - Validação da retenção de registros em estado de falha na outbox, auto-recuperação acionando `SettlementReconciliationService.reconcileOutboxIntegrity()` e garantia de consistência eventual até a projeção CQRS via leitura.
9. **Incremento 10 — Endpoint de Ingestão de Liquidação (POST /api/v1/settlements):**
   - `SettlementIngestionController`: Exposição do endpoint OpenAPI `POST /api/v1/settlements` aceitando `multipart/form-data` (`metadata` JSON e anexo opcional).
   - `SettlementRegistrationResult`: Diferenciação semântica entre nova persistência durável (`201 Created` com header `Location`) e replay idempotente (`200 OK`).
   - Mapeamento robusto de erros no `GlobalExceptionHandler`: `409 Conflict` para `IdempotencyConflictException`, `400 Bad Request` para `InvalidChargebackException` e validações multipart/header.
   - Cobertura com testes de slice (`SettlementIngestionControllerTest`) e integração (`SettlementIngestionControllerIT`) com Testcontainers e storage isolado em `${java.io.tmpdir}`.

## Status das Validações

- `.\mvnw.cmd test`: **130 testes unitários e de slice aprovados, 0 falhas**.
- `.\mvnw.cmd verify -DskipITs=false`: **50 testes de integração Testcontainers aprovados, 0 falhas**. Total: **180 testes**.
- Build local 100% verde no Windows e verificado; Incremento 10 concluído.
