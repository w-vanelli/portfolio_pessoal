# Estado do portfólio

Fase: Semana 2 - Incremento 1 (Domínio, Persistência JPA e Contrato de Storage) concluído com sucesso. Próxima etapa: Semana 2 - Incremento 2 (Implementação do StagingStorageService com NIO.2 e Testcontainers).

## Objetivo e restrições

- **Profissional:** Wellington Vanelli (18+ anos de experiência, Curitiba/PR).
- **Core técnico:** Java moderno (Spring Boot 3.x, Quarkus, Java 21), microsserviços cloud-native, arquitetura distribuída, mensageria (RabbitMQ), concorrência e integridade em RDBMS (PostgreSQL, Oracle PL/SQL), containers (Docker, OpenShift) e desenvolvimento assistido por IA (Gemini Code Assist, Antigravity).
- **Alvo pretendido:** Mercado Internacional / Global Remote em Inglês (com bio de perfil bilíngue).
- **Regime alvo:** Contratos Part-Time / Noturnos (período após 17h BRT).
- **Disponibilidade e prazo:** 4 horas semanais com prazo de 4 semanas para a 1ª entrega pública (esforço planejado: ~16 horas de engenharia).
- **Perfil do GitHub:** `https://github.com/w-vanelli` (atualmente com 0 repositórios públicos; ponto de partida "Do zero" em presença pública).

### Fontes analisadas (`portfolio-private/`)

1. `Curriculum_Vitae_-_Wellington_Vanelli_2026.pdf`: Trajetória completa em PT-BR (18 anos), atuação em projetos governamentais e financeiros.
2. `Profile.pdf`: Exportação do perfil do LinkedIn em PT-BR.
3. `wellington-vanelli-resume.pdf`: Resume em inglês focado em "Senior Backend Java Engineer | AI Code Reviewer & Systems Specialist".
4. `Wellington_Vanelli_Resume_Part_Time_Ready.pdf`: Resume em inglês focado em modelo Part-Time.
5. `pkp.json`: Personal Knowledge Pack estruturado (v1.1.0).
6. `questions_and_answers.md`: Documento aprofundado de entrevistas detalhando raciocínio arquitetural, resolução de concorrência na JVM, decoupling NIO.2 vs JPA, e DDD com bounded contexts em projetos anteriores.

## Inventário e distinção de evidências

- **Experiência declarada:** 18+ anos em engenharia de backend corporativo em sistemas governamentais e financeiros.
- **Raciocínio técnico e julgamento observado:** Alta maturidade comprovada nos memoriais técnicos (concorrência JVM, desacoplamento transacional de I/O em staging, RabbitMQ).
- **Evidência pública técnica (GitHub):** Ponto de partida estabelecido na Semana 1 com repositório de perfil e scaffold do projeto âncora `ledger-stream`.

## Decisões

- Não reproduzir nem reimplementar nenhuma funcionalidade dos sistemas reais de empregadores anteriores, cumprindo integralmente `portfolio-context/originalidade.md`.
- **Projeto Principal Âncora:** `LedgerStream` – *Idempotent Event & Settlement Dispatcher* (Spring Boot 3.4.0, Java 21, PostgreSQL com locking e idempotência condicional, RabbitMQ com DLX/retries, storage com compensação, Testcontainers e OpenAPI).
- **Stack do Projeto Principal:** Java 21 + Spring Boot 3.4 + PostgreSQL + RabbitMQ + Docker/Testcontainers.
- **Idioma dos artefatos públicos:** Documentação e código em Inglês; bio e destaques no README de perfil bilíngues.

## Incremento atual (Semana 2 - Incremento 1: Domínio, Persistência JPA e Contrato de Storage)

Conclusão do **Primeiro Incremento da Semana 2**:
1. **Domínio e Value Objects:**
   - Implementado Value Object `MonetaryAmount` com validação estrita a `NUMERIC(15,2)` (máx. 13 dígitos inteiros e 2 decimais). Conversão direta `String -> BigDecimal` sem `double`. Rejeição de precisão excedente e de valores negativos.
   - Normalização canônica obrigatória definida via `setScale(2, RoundingMode.UNNECESSARY).toPlainString()` para cálculo estável de checksum SHA-256 de payload na idempotência.
   - Registrada pendência de domínio para regras de negócio sobre valor zero (`0.00`) e representação de débitos em estornos (`CHARGEBACK_ADJUSTMENT`).
   - Entidades JPA `SettlementEvent` e `SettlementAttachment` com ciclo de vida mapeado para enums `SettlementStatus`, `SettlementType` e `AttachmentStatus`.
2. **Persistência JPA:**
## Incremento atual (Semana 2 - Incremento 2: StagingStorageService com NIO.2 e Testcontainers)

Conclusão do **Segundo Incremento da Semana 2**:
1. **Regras Monetárias e Domínio:**
   - Implementadas regras `amount > 0` e precisão obrigatória via `NUMERIC(15,2)` e validação no domínio. Valores em zero e negativo são terminantemente rejeitados via código e Migration V2 (constraint). Normalização de decimais ("1" para "1.00").
   - Corrigida a afirmação sobre a regex monetária: atualizado no OpenAPI e domínio para `^(0|[1-9]\d{0,12})(\.\d{1,2})?$`.
2. **Mecânica de Chargeback:**
   - Entidade `SettlementEvent` mapeada com coluna FK `original_settlement_id` (migration V2). O ajuste de Chargeback nunca modifica ou apaga o original.
3. **Storage NIO.2 (Staging):**
   - Implementado `StagingStorageServiceImpl` com `java.nio.file`: método `compensateStaging` com `throws IOException`, verificações de Path Traversal, fallback ao não suportar `ATOMIC_MOVE`, e posse de `InputStreams` pelos clientes.
4. **Testes e Infraestrutura:**
   - Maven configurado para isolar Unit (`Surefire`) vs. Integration (`Failsafe`). Testcontainers com PostgreSQL 16 integrado no perfil `integration-test`.
   - Workflow CI (`ci.yml`) ajustado para cobrir `verify`.

## Entregas verificadas e Limitações

- Compilação e execução de testes unitários localmente (**49 testes executados com 0 falhas**).
- *Limitação / Impedimento Confirmado:* **Docker Indisponível**, impedindo a execução de testes de integração com Testcontainers na máquina host do ambiente. O Incremento 2 está totalmente implementado, mas com validação de integração pendente de container.
## Incremento atual (Semana 2 - Incremento 3: Serviço de Aplicação e Transações)

Conclusão do **Terceiro Incremento da Semana 2**:
1. **Semântica de Estados:**
   - Adicionada matriz rigorosa de transição ao `SettlementEvent` (`STAGED -> COMMITTED -> DISPATCHED` e fluxos de compensação). Transições proibidas lançam `IllegalStateException`.
2. **Fronteiras Transacionais e Recuperação:**
   - Implementado `SettlementApplicationService` isolando a transação JPA da promoção de I/O.
   - O `promoteToPermanent` é chamado de forma síncrona somente após confirmação do commit (`persist` isolado via `@Transactional`).
   - Arquivos órfãos de transações falhas ou colisões concorrentes (`DataIntegrityViolationException`) são devidamente compensados via `stagingStorageService.compensateStaging`.
3. **Idempotência e Conflitos:**
   - `ChecksumGenerator` garante integridade do payload usando SHA-256 no accountId, currency, amount, settlementType, description, originalSettlementId e dados de attachment.
   - Recuperação via leitura antes da escrita e no fallback de concorrência.
4. **Regras de Chargeback:**
   - Validação delegada ao serviço: chargeback exige referência a settlement original (não-chargeback), mantendo mesma conta e moeda. Original permanece inalterado.

## Entregas verificadas e Limitações

- Compilação e execução de testes unitários localmente (**59 testes executados com 0 falhas**).
- Validação de Integração com **Testcontainers (PostgreSQL 16)** executada com sucesso. Os testes confirmam:
  - Criação do schema (Flyway V1 e V2) no banco real;
  - Validações de Constraints (`CHECK amount > 0`) no nível do banco rejeitando dados inválidos independentemente do domínio;
  - Proteção de `UNIQUE` constraint da chave de idempotência;
  - Relacionamentos em cascata operando como esperado (`ON DELETE CASCADE`).

## Pendências e próximo passo

4. *(Outbox Transacional, Consumidor RabbitMQ e Reconciliação pós-crash continuam preservados para as etapas seguintes).*
