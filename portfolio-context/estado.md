# Estado do portfólio

Atualizado em 21/09/2026. Fase: Semana 2 — consolidação dos Incrementos 2 e 3.
O serviço do Incremento 3 já existe, mas sua validação completa permanece pendente.

## Base verificada e ambiente

- GitHub conectado: `w-vanelli/portfolio_pessoal`, branch `main`, commit
  `8edb76205a7a195c1b51987688de1aad80e5abd7`.
- Checkout criado no ambiente Linux do Work, inicialmente limpo. Não houve acesso à máquina Windows.
- Alterações preparadas e verificadas localmente; publicação na `main` autorizada pelo usuário em 21/09/2026. Nenhuma mudança foi aplicada à máquina Windows.
- Stack conferida: Java 21, Spring Boot 3.4.0, Maven 3.9.9, JPA/Hibernate, Flyway,
  PostgreSQL 16, Testcontainers 1.20.4. RabbitMQ 3.13 existe no Compose; mensageria não implementada.

## Objetivo e restrições

- **Profissional:** Wellington Vanelli (18+ anos de experiência, Curitiba/PR).
- **Core técnico:** Java moderno (Spring Boot 3.x, Quarkus, Java 21), microsserviços cloud-native, arquitetura distribuída, mensageria (RabbitMQ), concorrência e integridade em RDBMS (PostgreSQL, Oracle PL/SQL), containers (Docker, OpenShift) e desenvolvimento assistido por IA (Gemini Code Assist, Antigravity).
- **Alvo pretendido:** Mercado Internacional / Global Remote em Inglês (com bio de perfil bilíngue).
- **Regime alvo:** Contratos Part-Time / Noturnos (período após 17h BRT).
- **Disponibilidade e prazo:** 4 horas semanais com prazo de 4 semanas para a 1ª entrega pública (esforço planejado: ~16 horas de engenharia).
- **Perfil do GitHub:** `https://github.com/w-vanelli` (repositório público `portfolio_pessoal` inspecionado em 21/09/2026).

### Fontes anteriormente relatadas (`portfolio-private/`; não relidas nesta continuidade)

1. `Curriculum_Vitae_-_Wellington_Vanelli_2026.pdf`: Trajetória completa em PT-BR (18 anos), atuação em projetos governamentais e financeiros.
2. `Profile.pdf`: Exportação do perfil do LinkedIn em PT-BR.
3. `wellington-vanelli-resume.pdf`: Resume em inglês focado em "Senior Backend Java Engineer | AI Code Reviewer & Systems Specialist".
4. `Wellington_Vanelli_Resume_Part_Time_Ready.pdf`: Resume em inglês focado em modelo Part-Time.
5. `pkp.json`: Personal Knowledge Pack estruturado (v1.1.0).
6. `questions_and_answers.md`: Documento aprofundado de entrevistas detalhando raciocínio arquitetural, resolução de concorrência na JVM, decoupling NIO.2 vs JPA, e DDD com bounded contexts em projetos anteriores.

## Escopo e planejamento preservados

Projeto autoral independente para registrar eventos de settlement e futuramente despachá-los.
Não move dinheiro, não calcula saldo ou liquidação e não promete exactly-once ponta a ponta.
Documentação pública e código em inglês; continuidade interna em português.
Semana 1: fundação e contratos. Semana 2: domínio, schema, storage e serviço de aplicação.
Fases seguintes: outbox transacional, mensageria/consumidor resilientes, reconciliação pós-crash
baseada em estado persistido e demonstrações de falhas. A disponibilidade histórica de 4h/semana
permanece como referência de planejamento; não ampliar escopo por aparência de complexidade.

## O que foi encontrado no remoto

- MonetaryAmount, entidades, matriz de transição, repositórios e migrations V1/V2 presentes.
- Serviço de aplicação, checksum e validações de chargeback já presentes.
- Storage e seus testes ausentes do Git; `**/storage/` ignorava também pacotes Java.
- Chamada interna ao método `@Transactional`, sem fronteira de serviço via proxy;
  checksum sem bytes do anexo e com delimitadores ambíguos; limpeza em exceções genéricas;
  promoção sem atualização persistida do caminho/status do anexo.
- README desatualizado, misturando arquitetura futura e capacidades existentes.
- A execução remota [35452038814](https://github.com/w-vanelli/portfolio_pessoal/actions/runs/35452038814)
  falhou antes de compilar: o wrapper Unix tentava mover a distribuição Maven para dentro dela mesma.
- Totais históricos de 27/49/59 testes e validação local anterior de Testcontainers são relatos;
  não comprovam a integridade de um checkout limpo desse commit.

## Alterações locais implementadas

1. Correção dos wrappers Unix/Windows e da regra de ignore. Reconstrução do pacote NIO ausente,
   respeitando o contrato utilizado pelo serviço; não foi possível recuperar os arquivos ignorados da máquina Windows.
2. Stage por tentativa exclusiva, checksum dos bytes escritos, stream pertencente ao chamador,
   `ATOMIC_MOVE` obrigatório e reserva exclusiva do diretório de destino contra sobrescrita entre
   escritores da aplicação. Sem fallback não atômico; erros de limpeza observáveis.
3. Aceitação em `TransactionTemplate`; transação ambiente rejeitada antes de I/O.
   Limpeza após exceção de aceitação somente com rollback confirmado. Commit desconhecido preserva arquivos.
4. Promoção após commit; metadados de arquivo permanente gravados em segunda transação.
   Falha posterior não desfaz aceitação nem dispara compensação destrutiva.
5. Checksum completo com campos delimitados por comprimento, valor canônico, referência ao original
   e digest do anexo. Null e descrição vazia são distintos; moeda deve vir em três letras maiúsculas.
6. Agente Mockito explícito no Surefire/Failsafe para Java 21 e ciclo de vida compartilhado do PostgreSQL
   de testes corrigido para não encerrar o container sob um contexto Spring reutilizado.
7. Testes de regressão para checksum, filesystem, transações simuladas e testes de integração do serviço.
   README/OpenAPI/plano atualizados para distinguir implementação e contrato futuro.

## Validação observada nesta continuidade

- Bootstrap Unix em cache Maven inicialmente vazio: aprovado; Maven 3.9.9 executou.
- JDK 21 isolado usado no Work. Proxy e truststore do ambiente foram configurados apenas na
  execução local, fora do repositório; configurações da máquina Windows não foram acessadas.
- `./mvnw -o test --no-transfer-progress`: **61 testes, 0 falhas, 0 erros, 0 ignorados**.
- `./mvnw clean verify --no-transfer-progress` (com settings locais de rede): compilação e
  empacotamento aprovados; os mesmos 61 testes aprovados. Failsafe terminou com **19 erros de
  inicialização**, pois não encontrou Docker nem `/var/run/docker.sock`. Nenhum comportamento
  de integração PostgreSQL foi validado nesta execução. O build completo não foi aprovado nesse ambiente local.
- `git diff --check`: aprovado.
- Wrapper Windows: corrigido por inspeção, ainda não executado em Windows.
- CI remoto do commit `fcefe91`: aprovado em [35601974165](https://github.com/w-vanelli/portfolio_pessoal/actions/runs/35601974165), com 61 testes unitários e 19 de integração sem falhas. Esta evidência é do baseline anterior à outbox; o novo commit exige sua própria execução.

## Limitações e próximo incremento mínimo

- Baseline consolidado validado remotamente. Executar o CI da outbox após publicação para verificar V3 e as asserções de integração atualizadas.
- O checksum corrigido é incompatível com o anterior: registros existentes não são regravados;
  retries de chaves antigas podem retornar conflito. Preservar datasets retidos e planejar migração
  validada se forem necessários; não aceitar automaticamente o checksum antigo que omitia o anexo.
- Storage exige raízes controladas pela aplicação, sem escritores externos. Movimento atômico não
  garante durabilidade pós-queda. Diretórios de tentativas e promoções interrompidas exigem reconciliação futura.
- Falha entre promoção e atualização de metadados deixa referência STAGED, mas o destino é derivável
  do mesmo UUID. Ainda não há worker que reconcilie esse caso ou commit desconhecido.
- Validações unitárias com callbacks de transação simulados não demonstram recuperação após crash.
- Próximo avanço funcional implementado localmente: **outbox gravada na transação de aceitação**.
  Migration V3, `SettlementOutboxEntry`, `SettlementOutboxRepository` e testes de integração
  foram adicionados. A linha é única por settlement, nasce `PENDING` e não representa publicação.
  Não publicar diretamente no RabbitMQ como atalho. Até existir o dispatcher, o fluxo termina em
  COMMITTED com uma intenção de publicação persistida.

## Outbox — estado local desta continuidade

- V3 cria `settlement_outbox` com FK para o evento, unicidade por settlement, status, tentativas,
  `available_at`, timestamps e último erro.
- A entrada é escrita na mesma transação que grava o evento; falha no outbox deve desfazer a
  aceitação completa.
- O repositório já oferece consulta de lote por status e horário para o dispatcher futuro,
  sem alegar locking ou publicação concorrente nesta etapa.
- `PUBLISHED`/`FAILED` e métodos de atualização existem no modelo para a próxima etapa, mas
  nenhum componente os aciona ainda.

- Publicação da outbox autorizada pelo usuário; a tentativa anterior não foi executada por limite da revisão automática do conector. Retomada pelo mesmo conector, preservando o histórico.
- Validação local da outbox: 61 testes unitários/filesystem aprovados e testes de integração compilados. A integração da outbox aguarda execução do novo commit no CI.
