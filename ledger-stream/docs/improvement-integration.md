# Improvement Integration Plan

Este documento mapeia as recomendações de melhoria técnica para as fases do projeto, distinguindo o que já está na fundação estrutural do que será programado nas semanas seguintes.

## 1. Fundação e Contratos (Semana 1 - Concluída/Aprovada)
As melhorias incorporadas nesta etapa visam preparar o terreno, mas não implementam código de negócio ainda:
- **Contrato de Idempotência:** A OpenAPI agora prevê respostas `200 OK` (idempotência por replay seguro) e `409 Conflict` (violação de integridade ou checksum da chave) de forma clara.
- **Isolamento Transacional Documentado:** O README descreve que o armazenamento do anexo (`java.nio.file`) é um passo distinto do commit no banco, evitando misturá-los numa única transação atômica idealizada.
- **Definição Monetária (P1):** A representação monetária foi alinhada entre banco (`NUMERIC(15,2)`) e contrato (OpenAPI como `string` com padrão decimal `^\d+(\.\d{1,2})?$`) para evitar perda de precisão em ponto flutuante, corrigindo a divergência onde a API usava `double`.
- **Limpeza do Estado Projetado:** O material público do repositório foi readequado para não prometer garantias ou testes que ainda não foram desenvolvidos (ajustes no README).
- **Independência de Build:** A compilação local (Maven) foi desvinculada de mirrors corporativos e atestada com sucesso em ambiente local. A execução remota do CI via GitHub Actions encontra-se configurada, porém pendente de verificação no ambiente do provedor.

## 2. Implementação do Domínio, JPA e Storage (Semana 2 - Pendente/Planejada)
Nesta fase as seguintes recomendações serão efetivamente codificadas no backend:
- **Recuperação de Anexos e Limpeza:** O `StagingStorageService` terá mecanismo seguro para limpar arquivos orfãos (rollback de transação) e não apagar arquivos já ativos ou em transição. (Planejado)
- **Histórico de Auditoria Imutável:** Embora o banco tenha tabelas e `ON DELETE CASCADE` para limpeza macro, a lógica da aplicação fará os inserts no `settlement_audit_log` para cada mudança de status, não dependendo apenas do esquema do banco de dados para segurança de negócio. (Planejado)
- **Tratamento Efetivo de Idempotência:** Validação de duplicatas concorrentes com lock e checagem de checksum `payload_checksum`. (Planejado)

## 3. Mensageria e Resiliência (Fase Futura - A propor/Detalhar)
- **Outbox Transacional e Recuperação:** Mecanismo para persistir atomicamente o negócio e a "intenção" de notificação, garantindo retomada de falhas pós-commit. (A propor/Detalhar - Não alocado estritamente na Semana 2 original).
- **Consumidor Idempotente:** O consumidor demonstrativo deverá evitar repetição de efeitos protegidos no sistema destino (não confundir ACK do broker com execução em negócio). (A propor/Detalhar).
- **Recuperação Pós-Queda Abrupta e Cleanup:** A compensação não dependerá apenas de um callback limpo do RDBMS (que falha em queda súbita). A limpeza (cleanup) exigirá **reconciliação com estados persistidos e referências do banco de dados**, utilizando a idade do arquivo (TTL) apenas como critério complementar. (A propor/Detalhar).

## 4. Testes e Demonstrações (Fase de Validação Final)
- **Demonstrações de Recuperação:** Procedimentos reproduzíveis documentando falha de broker, retomada e conflitos de concorrência. (A propor/Detalhar).
- **Ativação do CI de Testes:** A ativação da fase de testes automatizados (`mvnw test`) no GitHub Actions será vinculada especificamente ao incremento que introduzir a primeira bateria de testes JUnit/Testcontainers. Até lá, o CI executará apenas verificação estática de build (`compile`).
