package br.com.oficina.execution.domain.model;

/** Estados de uma tarefa da fila. Os terminais não aceitam mais trabalho. */
public enum TaskStatus {
    QUEUED(false),
    IN_PROGRESS(false),
    FINISHED(true),
    FAILED(true),
    CANCELLED(true);

    private final boolean terminal;

    TaskStatus(boolean terminal) {
        this.terminal = terminal;
    }

    public boolean isTerminal() {
        return terminal;
    }
}
