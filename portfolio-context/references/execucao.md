# Construção no workspace

Antes de implementar, aplicar `portfolio-context/references/originalidade.md`. Confirmar que o escopo independente já foi revisado pelo profissional. Mudanças de escopo com risco de reproduzir funcionalidades reais exigem nova revisão da parte afetada.

## Preparar o incremento

Ler o estado e inspecionar os arquivos pertinentes, manifests e status do Git. Identificar sistema operacional, shell e comandos disponíveis antes de prescrever execução. Uma pasta de portfólio pode conter projetos independentes: não criar repositórios aninhados sem necessidade e sem explicar a organização.

Definir comportamento esperado e critérios de aceite; escolher uma entrega pequena que atravesse um fluxo real. Aproveitar scripts e convenções existentes. Se o usuário ainda não escolheu um projeto e faltam dados de carreira, concluir o diagnóstico antes de gerar uma aplicação arbitrária.

## Construir e verificar

Implementar o fluxo com validação e falhas relevantes. Escrever testes que protejam regras, integridade, concorrência ou contratos pertinentes; não criar testes cosméticos para documentação. Executar os comandos adequados de teste/build/lint disponíveis. Corrigir falhas introduzidas; distinguir bloqueios externos de bugs do código.

Em CI, adaptar workflow à stack real e confirmar versões em documentação oficial. Não inventar badge verde. Em bancos, usar migrações e dados sintéticos; não executar contra produção. Em deploy, considerar custo, reprodução e recuperação; um ambiente local bem documentado é aceitável quando hospedagem não for viável.

Documentar problema, arquitetura e trade-offs, setup reproduzível, configuração sem secrets, testes, operação, limitações e demo quando aplicável. Escolher controles de segurança por ameaça relevante. Mostrar logs, métricas ou rastreamento quando ajudarem a explicar diagnóstico de falhas.

## Encerrar e continuar

Registrar arquivos alterados, comandos executados, resultados, limitações e próximo passo no estado. Não declarar build/test/deploy bem-sucedido sem resultado observado. Só marcar como concluído o critério efetivamente verificado.

Antes de um commit autorizado, revisar diff e arquivos selecionados, garantindo que documentos privados não entrem. Se o usuário autorizar publicação, revisar autoria, licença, referências pessoais, URLs e credenciais antes do push. Não fabricar commits para simular trabalho em equipe.

Preparar o autor para explicar: problema, decisão, alternativa rejeitada, teste, falha e recuperação. Fazer exercícios pontuais se ele não conseguir explicar o código assistido por IA.
