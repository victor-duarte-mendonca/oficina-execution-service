package br.com.oficina.execution.domain.exception;

/** Regra de negócio da execução violada (transição inválida, dado incompleto). Responde 422 na API. */
public class ExecutionRuleViolation extends RuntimeException {

    public ExecutionRuleViolation(String message) {
        super(message);
    }
}
