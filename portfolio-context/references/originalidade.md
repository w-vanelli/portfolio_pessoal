# Originalidade e confidencialidade — requisito do usuário

Usar a experiência profissional exclusivamente para identificar competências abstratas e verificáveis. Não usar projetos reais como especificação, modelo de produto ou fonte de funcionalidades do portfólio.

## Limites obrigatórios

- Não copiar nem reimplementar funcionalidades identificáveis de projetos reais nos quais o profissional trabalhou, mesmo com código novo, nomes alterados, outra stack ou dados sintéticos.
- Não reproduzir regras de negócio, fluxos, telas, contratos de API, modelos de dados, consultas, algoritmos proprietários, diagramas, integrações ou combinações características desses projetos.
- Não incluir código, documentação interna, dados, screenshots ou conteúdo protegido sem direito de uso; não solicitar esses materiais como condição para o diagnóstico.
- Não praticar plágio. Para dependências ou referências públicas legítimas, verificar licença, preservar atribuições exigidas e distinguir claramente a contribuição autoral. O projeto não precisa reinventar bibliotecas ou padrões públicos.
- Anonimização, troca de domínio ou dados sintéticos isoladamente não tornam uma reprodução aceitável. O projeto demonstrativo deve ser concebido de forma independente.

## Processo de concepção independente

1. Extrair somente competências gerais a partir de descrições autorizadas: por exemplo, transações, testes, idempotência, diagnóstico ou modelagem relacional. Não registrar detalhes confidenciais para provar a competência.
2. Propor um problema novo a partir de necessidade hipotética ou fonte pública citável, com público, escopo, requisitos e critérios de aceite próprios.
3. Relacionar competência → evidência técnica → teste/demonstração. Não construir um mapeamento funcional entre o sistema real e o portfólio.
4. Antes de implementar, apresentar o escopo ao profissional e pedir que confirme que não reproduz funcionalidades ou elementos confidenciais dos projetos em que trabalhou. Essa confirmação é necessária porque o agente não conhece todos os sistemas reais. Se já houver confirmação para aquele escopo, prosseguir; repetir apenas se mudanças alterarem essa avaliação.
5. Se houver semelhança identificável ou dúvida material, interromper somente a parte afetada e propor uma solução independente. Não pedir autorização para flexibilizar a proibição.
6. Antes da publicação, revisar o diff, documentação, demonstrações e dados; registrar origem pública de referências/dependências e eliminar detalhes de origem profissional. Não prometer garantia jurídica de ausência de infração.

## Evidência esperada

Registrar em `portfolio-context/originalidade.md` o problema independente, competências demonstradas, fontes públicas e dependências, contribuição autoral, revisão do profissional e pendências. Não registrar nomes ou funcionalidades confidenciais do sistema real, nem produzir tabelas de equivalência entre produtos.

Explicar no README, quando pertinente: “Projeto demonstrativo independente, com requisitos próprios, criado para evidenciar competências de engenharia de software. Não reproduz sistemas de empregadores ou clientes.” Usar essa declaração somente após revisão; ela não substitui a verificação.
