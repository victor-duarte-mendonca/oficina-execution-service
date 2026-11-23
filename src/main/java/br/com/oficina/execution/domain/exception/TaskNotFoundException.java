package br.com.oficina.execution.domain.exception;

import br.com.oficina.execution.domain.model.Phase;

/** A OS não tem tarefa na fase pedida. Responde 404 na API. */
public class TaskNotFoundException extends RuntimeException {

    public TaskNotFoundException(Long workOrderId, Phase phase) {
        super("A OS " + workOrderId + " não tem tarefa de " + phase);
    }
}
