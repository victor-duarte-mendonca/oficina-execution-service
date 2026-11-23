package br.com.oficina.execution.domain.event;

import br.com.oficina.execution.domain.model.Phase;

/**
 * O que aconteceu com uma tarefa e precisa ser comunicado ao OS (catálogo de eventos da spec 03 §3.2).
 * O agregado registra o fato; a aplicação o transforma na mensagem do contrato.
 */
public sealed interface ExecutionEvent {

    /** Nome do tipo no contrato de mensagens. */
    String type();

    record Enqueued() implements ExecutionEvent {
        @Override public String type() { return "ExecutionEnqueued"; }
    }

    record EnqueueRefused(String reason) implements ExecutionEvent {
        @Override public String type() { return "ExecutionEnqueueFailed"; }
    }

    record Started(String assignee) implements ExecutionEvent {
        @Override public String type() { return "ExecutionStarted"; }
    }

    record Finished(String summary) implements ExecutionEvent {
        @Override public String type() { return "ExecutionFinished"; }
    }

    record Failed(String reason) implements ExecutionEvent {
        @Override public String type() { return "ExecutionFailed"; }
    }

    record Cancelled(boolean alreadyFinished) implements ExecutionEvent {
        @Override public String type() { return "ExecutionCancelled"; }
    }

    /** Informativo: não move a Saga, alimenta o histórico do OS. */
    record Progress(Phase phase, String note) implements ExecutionEvent {
        @Override public String type() { return "ExecutionProgress"; }
    }
}
