# Registro de Originalidade e Independência Funcional

Este documento registra a concepção independente do projeto demonstrativo para o portfólio de **Wellington Vanelli**, em estrito cumprimento às diretrizes de confidencialidade e originalidade.

---

## 1. Princípio de Isolamento

A trajetória profissional corporativa (incluindo atuações na CELEPAR, SEFA/PR, DER/PR, HSBC, GVT e B2W) é utilizada **exclusivamente como fonte de identificação de competências abstratas de engenharia de software**. 

É vedada e não foi realizada nenhuma reprodução de:
- Regras de negócio, cálculos tributários, normas fiscais estaduais ou trâmites de autuação de trânsito.
- Modelos de dados, schemas de bancos de dados legados ou contratos de API proprietários.
- Código-fonte, scripts, bibliotecas internas, relatórios confidenciais ou massas de dados reais.

---

## 2. Projeto Âncora Independente: LedgerStream

### Problema Conceitual
Processamento de eventos financeiros assíncronos e conciliação de faturas (*billing & settlements*) em uma plataforma SaaS B2B hipotética que recebe webhooks e mensagens de liquidação financeira com risco de duplicação de rede e tráfego concorrente.

### Competências Abstratas Demonstradas
- **Controle de Concorrência e Idempotência:** Garantia de processamento único de eventos através de índices únicos condicionais no PostgreSQL e locks pessimistas controlados.
- **Padrão Transacional de Duas Fases com Compensação:** Gravação de comprovantes em storage (staging) com confirmação amarrada ao commit do banco de dados relacional e rotina de compensação para deleção em caso de rollback.
- **Mensageria Desacoplada e Resiliência:** Publicação e consumo assíncrono via RabbitMQ com Dead-Letter Exchange (DLX), retries com backoff exponencial e Circuit Breaker.
- **Arquitetura Limpa e Contratos Públicos:** Separação estrita de camadas de domínio, aplicação e infraestrutura, com contratos documentados via OpenAPI 3.0 / Swagger.
- **Testabilidade Orientada a Riscos:** Testes de concorrência com múltiplas threads e testes de integração de ponta a ponta com Testcontainers (PostgreSQL + RabbitMQ).

### Fontes Públicas e Dependências Legítimas
- Frameworks open source sob licença Apache 2.0 / MIT: Spring Boot 3.x, Spring Data JPA, Spring AMQP, Resilience4j, Flyway, Testcontainers, JUnit 5, AssertJ.
- Padrões arquiteturais públicos e documentados na literatura aberta (Enterprise Integration Patterns, Two-Phase Commit Pattern, Transactional Outbox Pattern).

### Contribuição Autoral
Código, diagramas, configurações de CI/CD e documentação técnica concebidos 100% de forma autoral a partir do zero no workspace.

### Revisão e Confirmação do Profissional
- [x] Confirmação do usuário em 13/09/2026 de que o escopo funcional e os contratos do projeto `LedgerStream` não reproduzem especificações ou elementos confidenciais de empregadores anteriores.
