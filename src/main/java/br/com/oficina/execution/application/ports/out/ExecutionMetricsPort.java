package br.com.oficina.execution.application.ports.out;

/** Métricas de negócio da execução (spec 06 §5 e spec 13 §3). */
public interface ExecutionMetricsPort {

    /** Evento publicado para o OS (ex.: {@code ExecutionFailed}): base de "falhas de execução". */
    void eventPublished(String type);

    /** Comando da Saga tratado, por desfecho ({@code processed}, {@code duplicate}, {@code skipped}, {@code refused}). */
    void commandHandled(String type, String outcome);
}
