package br.com.oficina.execution.application.ports.out;

/**
 * Grava um {@link ExecutionChanges} de forma atômica: ou tudo (tarefas, notas, outbox, inbox), ou nada.
 *
 * <p>Trava otimista por tarefa: se outra gravação mudou a tarefa depois da leitura, lança
 * {@link ConcurrentTaskUpdateException} — a mensagem volta à fila e é reprocessada sobre o estado novo.
 * Se a mensagem de origem já estava no inbox, lança {@link DuplicateMessageException} e nada é gravado.</p>
 */
public interface ExecutionUnitOfWork {

    void commit(ExecutionChanges changes);

    final class DuplicateMessageException extends RuntimeException {
        public DuplicateMessageException(String messageId) {
            super("Mensagem já processada: " + messageId);
        }
    }

    final class ConcurrentTaskUpdateException extends RuntimeException {
        public ConcurrentTaskUpdateException(String detail) {
            super("A tarefa foi alterada por outro processamento: " + detail);
        }
    }
}
