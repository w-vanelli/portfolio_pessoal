# Estado do portfólio

Fase: Semana 1 (Fundação & Contratos Técnicos) concluída com sucesso. Próxima etapa: Semana 2 (Domínio, JPA e Storage com Compensação).

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

## Incremento atual (Correções Pós-Auditoria da Semana 1)

Conclusão da **Semana 1: Fundação & Contratos Técnicos**:
1. Repositório de perfil criado e alinhado para não prometer implementações ainda não finalizadas.
2. Scaffold do projeto âncora criado (`pom.xml`, `docker-compose.yml`, `openapi.yaml`, `V1__init_ledger.sql`, `application.yml`, scripts Maven).
3. **Melhorias Aplicadas e Validadas:**
   - Limpeza das configurações Maven corporativas (`settings.xml`), restabelecendo o acesso padrão ao Maven Central sem referências ou credenciais ao antigo empregador.
   - Correção do tipo monetário no OpenAPI para `string` (Regex decimal), prevenindo perda de precisão flutuante e alinhando com o banco `NUMERIC(15,2)`. Negativos não são permitidos. Representações equivalentes exigirão normalização em código futuro.
   - O workflow de CI (`ci.yml`) foi movido para a raiz (`.github/workflows/`), ajustando caminhos para o `ledger-stream` e validando apenas compilação.

## Entregas verificadas

- Confirmação formal de originalidade atestando que não há uso de funcionalidades confidenciais em `portfolio-context/originalidade.md`.
- Compilação local (`mvnw clean compile`) **aprovada com sucesso**, resolvendo artefatos diretamente do Maven Central. (Nota: O CI remoto do GitHub Actions foi configurado mas ainda não foi ativado remotamente).
- Documento de rastreio de melhorias criado em `ledger-stream/docs/improvement-integration.md`.

## Pendências e próximo passo

Iniciar a **Semana 2: Domínio, Persistência JPA & Storage com Compensação Transacional**:
1. Criação das entidades `SettlementEvent` e `SettlementAttachment` e repositórios Spring Data JPA. (Validação numérica backend inclusa).
2. Implementação do `StagingStorageService` com `java.nio.file` e lógica de confirmação/compensação pós-commit.
3. Criação de testes unitários automatizados. A ativação da fase de `test` no CI remoto ocorrerá junto desta entrega.
*(Garantias de infraestrutura como Outbox, Consumidor Idempotente e reconciliação de Cleanup de arquivos estão mapeadas para o futuro).*
