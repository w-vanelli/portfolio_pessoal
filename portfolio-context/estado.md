# Estado do portfólio

Fase: Semana 1 (Fundação & Contratos Técnicos) concluída com sucesso. Próxima etapa: Semana 2 (Domínio, JPA e Storage com Compensação).

## Objetivo e restrições

- **Profissional:** Wellington Vanelli (18+ anos de experiência, Curitiba/PR).
- **Core técnico:** Java moderno (Spring Boot 3.x, Quarkus, Java 21), microsserviços cloud-native, arquitetura distribuída, mensageria (RabbitMQ), concorrência e integridade em RDBMS (PostgreSQL, Oracle PL/SQL), containers (Docker, OpenShift) e desenvolvimento assistido por IA (Gemini Code Assist, Antigravity).
- **Alvo pretendido:** Mercado Internacional / Global Remote em Inglês (com bio de perfil bilíngue).
- **Regime alvo:** Contratos Part-Time / Noturnos (período após 17h BRT).
- **Disponibilidade e prazo:** 4 horas semanais com prazo de 4 semanas para a 1ª entrega pública (esforço planejado: ~16 horas de engenharia).
- **Perfil do GitHub:** `https://github.com/w-vanelli` (atualmente com 0 repositórios públicos; ponto de partida "Do zero" em presença pública).

## Fontes analisadas (`portfolio-private/`)

1. `Curriculum_Vitae_-_Wellington_Vanelli_2026.pdf`: Trajetória completa em PT-BR (18 anos), atuação na CELEPAR (2012–atual), Intellyone/GVT, Carvajal, BRQ/HSBC, GLT/HSBC, Enabler/Wipro/B2W. Pós-graduação UNESC (Cloud Native, 2025–2026).
2. `Profile.pdf`: Exportação do perfil do LinkedIn em PT-BR.
3. `wellington-vanelli-resume.pdf`: Resume em inglês focado em "Senior Backend Java Engineer | AI Code Reviewer & Systems Specialist", Remote Ready (Worldwide), C1 Advanced.
4. `Wellington_Vanelli_Resume_Part_Time_Ready.pdf`: Resume em inglês focado em modelo Part-Time (noites após 17h BRT).
5. `pkp.json`: Personal Knowledge Pack estruturado (v1.1.0) mapeando histórico, papéis, competências em nível EXPERT/ADVANCED e tags técnicas.
6. `questions_and_answers.md`: Documento aprofundado de entrevistas técnicas detalhando projetos reais na CELEPAR (PIR, Protesto DER, SIDER/CND), raciocínio arquitetural, resolução de concorrência na JVM (Thread Confinement, pool JBoss), decoupling NIO.2 vs JPA, e DDD com bounded contexts.

## Inventário e distinção de evidências

- **Experiência declarada:** 18+ anos em engenharia de backend corporativo em sistemas governamentais e financeiros (declarada com consistência nas fontes).
- **Raciocínio técnico e julgamento observado:** Alta maturidade comprovada nos memoriais técnicos (concorrência JVM, desacoplamento transacional de I/O em staging com compensação, partição por DDD e resiliência com RabbitMQ).
- **Evidência pública técnica (GitHub):** Ponto de partida estabelecido na Semana 1 com repositório de perfil e scaffold do projeto âncora `ledger-stream`.

## Decisões

- Não reproduzir nem reimplementar nenhuma funcionalidade dos sistemas reais anteriores (CELEPAR, DER, SEFA, HSBC, B2W), cumprindo integralmente `portfolio-context/originalidade.md`.
- **Projeto Principal Âncora:** `LedgerStream` — *Idempotent Event & Settlement Dispatcher* (Spring Boot 3.4.0, Java 21, PostgreSQL com locking e idempotência condicional, RabbitMQ com DLX/retries, storage com transação em duas fases e compensação, Testcontainers e OpenAPI).
- **Stack do Projeto Principal:** Java 21 + Spring Boot 3.4 + PostgreSQL + RabbitMQ + Docker/Testcontainers.
- **Idioma dos artefatos públicos:** Documentação e código em Inglês; bio e destaques no README de perfil bilíngues.

## Incremento atual

Conclusão da **Semana 1: Fundação & Contratos Técnicos**:
1. Repositório de perfil criado em `w-vanelli/README.md` (posicionamento internacional sênior, biografia técnica, competências e destaques).
2. Scaffold do projeto âncora criado em `ledger-stream/` com `pom.xml` (Java 21 / Spring Boot 3.4), `docker-compose.yml` (PostgreSQL 16 + RabbitMQ Management), `openapi.yaml` (OpenAPI 3.0), migração Flyway `V1__init_ledger.sql`, `application.yml`, scripts `mvnw`/`mvnw.cmd`, workflow de CI (`ci.yml`) e documentação de contratação em `README.md`.

## Entregas verificadas

- Confirmação formal de originalidade registrada em [portfolio-context/originalidade.md](file:///c:/Users/spina/Workspaces/portfolio_pessoal/portfolio-context/originalidade.md).
- Validação estrutural de todos os arquivos de configuração, OpenAPI e scripts SQL.
- Criação do walkthrough da Semana 1 em [walkthrough.md](file:///C:/Users/spina/.gemini/antigravity-ide/brain/d8a2561a-05e7-4918-b1ac-44c3dcb0f0b7/walkthrough.md).

## Pendências e próximo passo

Iniciar a **Semana 2: Domínio, Persistência JPA & Storage com Compensação Transacional**:
1. Criação das entidades `SettlementEvent` e `SettlementAttachment` e repositórios Spring Data JPA.
2. Implementação do `StagingStorageService` com `java.nio.file` e lógica de confirmação/compensação pós-commit.
3. Criação de testes unitários com JUnit 5 / AssertJ simulando rollback e validando a expurgação de arquivos órfãos.
