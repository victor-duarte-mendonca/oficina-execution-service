package br.com.oficina.execution.domain.model;

import br.com.oficina.execution.domain.exception.ExecutionRuleViolation;

/** Uma peça ou serviço a executar. Sem preço: valores são assunto do Billing. */
public record WorkItem(Kind kind, String name, int quantity) {

    public enum Kind { PART, SERVICE }

    public WorkItem {
        if (kind == null || name == null || name.isBlank() || quantity < 1) {
            throw new ExecutionRuleViolation("Item de execução inválido: " + name);
        }
    }
}
