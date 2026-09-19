# Improvement Integration Plan

Este documento mapeia as recomendações de melhoria técnica para as fases do projeto, distinguindo o que já está na fundação estrutural do que será programado nas semanas seguintes.

## 1. Fundação e Contratos (Semana 1 - Concluída/Aprovada)
As melhorias incorporadas nesta etapa visam preparar o terreno, mas não implementam código de negócio ainda:
- **Contrato de Idempotência:** A OpenAPI agora prevê respostas `200 OK` (idempotência por replay seguro) e `409 Conflict` (violação de integridade ou checksum da chave) de forma clara.
- **Isolamento Transacional Documentado:** O README descreve que o armazenamento do anexo (`java.nio.file`) é um passo distinto do commit no banco, evitando misturá-los numa única transação atômica idealizada.
- **Definição Monetária (P1):** A representação monetária foi alinhada entre banco (`NUMERIC(15,2)`) e contrato (OpenAPI como `string`). Corrige-se a afirmação de que a regex anterior (`^\d+(\.\d{1,2})?$`) garantia valor estritamente positivo: ela aceitava zero e não limitava os dígitos inteiros. O contrato OpenAPI e o domínio foram atualizados para `^(0|[1-9]\d{0,12})(\.\d{1,2})?$`, limitando estritamente a 13 dígitos inteiros (teto de `NUMERIC(15,2)`).
- **Limpeza do Estado Projetado:** O material público do repositório foi readequado para não prometer garantias ou testes que ainda não foram desenvolvidos (ajustes no README).
- **Independência de Build:** A compilação local (Maven) foi desvinculada de mirrors corporativos e comprovada com sucesso em ambiente local. A execução remota do CI via GitHub Actions encontra-se registrada como **não verificada remotamente** por ausência de evidência de execução no ambiente do provedor.

## 2. Implementação do Domínio, JPA e Storage (Semana 2)

### Incremento 1: Domínio, Persistência JPA e Contrato de Storage (Concluído e Testado)
- **Modelos de Domínio e Enums:** Entidades JPA `SettlementEvent` e `SettlementAttachment`, com enums `SettlementStatus`, `SettlementType` e `AttachmentStatus`.
- **Validação Monetária (Value Object `MonetaryAmount`):** Conversão direta `String -> BigDecimal` sem conversão intermediária por `double`. Validação estrita aos limites de `NUMERIC(15,2)` (máx. 13 dígitos inteiros e 2 decimais). Rejeição de precisão excedente (> 2 casas) sem arredondamento silencioso. Rejeição de valores negativos.
- **Normalização para Idempotência:** Normalização canônica obrigatória via `setScale(2, RoundingMode.UNNECESSARY).toPlainString()` garantindo que representações equivalentes (ex.: `"150.5"` e `"150.50"`) gerem checksums SHA-256 idênticos para comparação de payload.
- **Pendência Aberta de Domínio:** Registra-se como pendência a definição de negócio sobre aceitação de valor zero (`0.00`) e a modelagem contábil de estornos (`CHARGEBACK_ADJUSTMENT`) antes de assumir uma regra restritiva ou permissiva no domínio.
- **Repositórios Spring Data JPA:** `SettlementEventRepository` (com `findByIdempotencyKey`, `existsByIdempotencyKey`, `findByAccountIdAndStatus`) e `SettlementAttachmentRepository`.
- **Contrato de Storage:** Interface desacoplada `StagingStorageService` e record `StagingTicket` estabelecendo o contrato de fronteira (stage, promote, compensate) sem amarrar I/O físico à transação JPA.
- **Testes Unitários:** 27 testes unitários automatizados cobrindo invariantes monetárias, entidades e tickets de staging. *Importante:* Os testes unitários comprovam a lógica em memória e **não são apresentados como comprovação de integração com PostgreSQL ou de recuperação física de arquivos**.
- **Ativação no CI:** Atualização do workflow `.github/workflows/ci.yml` para executar `./mvnw clean test --no-transfer-progress`.

### 2.2. Incremento 2: Regras Monetárias, Storage e Schema

**Objetivo:** Consolidar e blindar a estrutura subjacente (valor, storage, schema) antes de avançar para os casos de uso complexos.

**O que foi implementado (Decisões aprovadas):**
*   **Regras Monetárias e Domínio:**
    *   Valores devem ser estritamente positivos (`amount > 0`). A representação de zero e números negativos foi sumariamente rejeitada por restrição de Domínio (`MonetaryAmount.java`) e por constraint no banco (migration V2 `CHECK`).
    *   A precisão foi cravada em 2 casas decimais, limitando o limite a `NUMERIC(15,2)` (0.01 a 9,999,999,999,999.99).
    *   Representações decimais divergentes na API (ex. "1", "1.0", "1.00") são normalizadas de forma determinística ("1.00") antes do checksum de idempotência.
*   **Estornos (Chargeback):**
    *   Foi introduzida a coluna `original_settlement_id` (migration V2) e o mapeamento respectivo em `SettlementEvent.java`.
    *   Ficou definido que estornos mantêm o original intacto. A validação das regras será aplicada no serviço de aplicação em incrementos posteriores.
*   **Armazenamento em 2 Fases (NIO.2):**
    *   A interface `StagingStorageService` e a implementação `StagingStorageServiceImpl` agora garantem:
        1. Criação de nomes em `UUID` para os stages com controle contra Path Traversal.
        2. Clean-up explícito em falhas (deleteIfExists).
        3. `promoteToPermanent` via ATOMIC_MOVE com fallback para modo comum (nunca usa REPLACE_EXISTING).
        4. O `compensateStaging` se tornou idempotente caso o arquivo falte, e propaga I/O de maneira transparente (sem warns invisíveis).
*   **Testes e Infra:**
    *   Failsafe inserido para rodar os `*IT` em Testcontainers PostgreSQL 16 (fases de `integration-test`).
    *   RabbitMQ e auto-configuração desativados no profile de testes.
    *   Workflow do Github `ci.yml` atualizado para `./mvnw clean verify`.

**Status Final do Incremento 2:**
*   Implementado com sucesso.
*   Testes Unitários: ✅ (49 passando)
*   Testes de Integração: ⏸️ Implementado, porém com **validação pendente** (Testcontainers bloqueado devido à indisponibilidade do Docker Desktop no ambiente atual).
*   **Pendência Crítica Resolvida:** A matriz de estado das transações foi implementada e validada, com transições estritas.

### 2.3. Incremento 3: Serviço de Aplicação e Transações (Concluído)

**Objetivo:** Integrar domínio, repositórios e storage em um serviço transacional com idempotência e limites de I/O claros.

**O que foi implementado:**
*   **Fronteiras Transacionais:** O registro do settlement (`SettlementApplicationService`) isola rigorosamente a transação em banco (`@Transactional`) da operação I/O física de promoção. O `promoteToPermanent` é executado somente após confirmação do `commit`. Se a promoção falhar, o status no banco permanece `COMMITTED` e os arquivos são preservados para recuperação posterior (orphan reconciliation).
*   **Idempotência Robusta:** Utilização do `ChecksumGenerator` padronizado. Em caso de colisão de chave, o sistema identifica se o payload é idêntico (retornando sucesso idempotente) ou divergente (lançando `IdempotencyConflictException`). Exceções de unicidade (`DataIntegrityViolationException`) são tratadas gracefully limpando apenas os arquivos em `STAGED` do processo perdedor.
*   **Regras de Chargeback:** Validado o encadeamento de um estorno para garantir que faz referência a um evento original real (não apaga ou altera) e pertence à mesma conta e moeda original, impedindo estorno de estorno.

**Status Final do Incremento 3:**
*   Implementado com sucesso.
*   Testes Unitários: ✅ (59 passando, cobertura das falhas e transições lógicas).
*   Testes de Integração: ✅ Validação completa (Testcontainers + PostgreSQL 16) confirmando restrições de schema (Flyway), uniqueness de idempotência e cascatas.

### 2.4. Próximos Passos
*   Implementação do Outbox Transacional.

## 3. Mensageria e Resiliência (Fase Futura - Mantida no Planejamento Macro)
- **Outbox Transacional e Recuperação:** Mecanismo para persistir atomicamente o evento e a intenção de notificação, garantindo retomada de falhas pós-commit com critérios verificáveis de aceitação. (Planejado).
- **Consumidor Idempotente:** Consumo com verificação de efeito em negócio e isolamento de ACK do broker. (Planejado).
- **Recuperação Pós-Queda Abrupta e Cleanup:** Rotina de reconciliação de arquivos órfãos baseada em cruzamento de estado com o banco de dados (usando TTL apenas como filtro complementar). (Planejado).

## 4. Testes e Demonstrações (Status Atual e Futuro)
- **Testes Unitários Atuais:** Executados localmente com sucesso (27/27 aprovados) e integrados ao script de CI.
- **Testes de Integração e Resiliência (Futuro):** Demonstrações de concorrência com threads simultâneas, simulação de falha de broker e rollback de transação.
