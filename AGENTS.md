
<!-- portfolio-architect:start -->
## Trabalho de portfólio
Para tarefas do portfólio, ler `.agents/rules/portfolio.md` e `portfolio-context/estado.md`.
Usar `/portfolio-cli` no Antigravity CLI para iniciar o processo especializado.
<!-- portfolio-architect:end -->

<!-- dev-router:start -->
## Orquestração Mestre e Delegação de Código (dev-router)
1. **Regra de Delegação de Implementação**: Ao receber solicitações para implementar novas funcionalidades, criar endpoints, realizar refatorações estruturais ou corrigir bugs complexos no código-fonte, o agente NÃO deve escrever o código diretamente de forma monolítica.
2. **Despacho Obrigatório**: O agente DEVE formular uma especificação clara com os critérios de aceite e delegar a execução via terminal chamando o orquestrador mestre `dev`:
   ```bash
   dev "<descrição detalhada da tarefa com requisitos técnicos e restrições>"
   ```
3. **Ciclo de Governança**: O `dev` selecionará dinamicamente o modelo ideal (Codex GPT-6 Luna/Sol/Astra ou Antigravity Claude/Gemini), gerenciará o esforço de raciocínio, escalonará em falhas de compilação/testes e acionará a revisão independente somente leitura.
4. **Fechamento**: Após a conclusão do `dev`, o agente deve inspecionar o `git diff` e `git status` e apresentar o resumo da entrega ao usuário.
5. **Exceções**: Ajustes triviais de documentação/typos, comandos de diagnóstico e esclarecimento de dúvidas conceituais podem ser atendidos diretamente pelo chat sem acionar o `dev`.
<!-- dev-router:end -->
