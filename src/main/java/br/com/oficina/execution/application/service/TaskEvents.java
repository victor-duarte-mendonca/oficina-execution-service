package br.com.oficina.execution.application.service;

import br.com.oficina.execution.application.messaging.ExecutionMessageFactory;
import br.com.oficina.execution.application.ports.out.ExecutionChanges;
import br.com.oficina.execution.application.ports.out.ExecutionTaskRepository;
import br.com.oficina.execution.domain.model.ExecutionTask;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Leva uma tarefa alterada para o lote de gravação: a própria tarefa e uma mensagem por evento que ela
 * registrou. É o único lugar que transforma eventos de domínio em mensagens — nenhum caso de uso
 * monta resposta à Saga "à mão".
 */
@ApplicationScoped
public class TaskEvents {

    private final ExecutionTaskRepository repository;
    private final ExecutionMessageFactory messages;

    public TaskEvents(ExecutionTaskRepository repository, ExecutionMessageFactory messages) {
        this.repository = repository;
        this.messages = messages;
    }

    public ExecutionChanges.Builder record(ExecutionChanges.Builder changes, ExecutionTask task, UUID causationId,
                                           OffsetDateTime now) {
        changes.save(task);
        task.pullEvents().forEach(event -> changes.publish(
            messages.from(event, task, () -> repository.positionInQueue(task), causationId, now)));
        return changes;
    }
}
