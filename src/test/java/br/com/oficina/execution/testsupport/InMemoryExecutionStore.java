package br.com.oficina.execution.testsupport;

import br.com.oficina.execution.application.messaging.OutboundMessage;
import br.com.oficina.execution.application.ports.out.ExecutionChanges;
import br.com.oficina.execution.application.ports.out.ExecutionTaskRepository;
import br.com.oficina.execution.application.ports.out.ExecutionUnitOfWork;
import br.com.oficina.execution.domain.model.ExecutionTask;
import br.com.oficina.execution.domain.model.Phase;
import br.com.oficina.execution.domain.model.TaskStatus;
import br.com.oficina.execution.domain.model.TechnicalNote;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Implementação em memória das portas de persistência, com as mesmas garantias do DynamoDB que importam
 * para a aplicação: gravação tudo-ou-nada, versão otimista por tarefa e inbox condicional. Permite
 * testar os casos de uso sem contêiner.
 */
public final class InMemoryExecutionStore implements ExecutionTaskRepository, ExecutionUnitOfWork {

    private record Key(Long workOrderId, Phase phase) {
    }

    /** Mesma ordem da chave do GSI1 no DynamoDB: prioridade, entrada na fila, número da OS. */
    private static final Comparator<ExecutionTask> QUEUE_ORDER = Comparator.comparingInt(ExecutionTask::priority)
        .thenComparing(ExecutionTask::queuedAt)
        .thenComparing(ExecutionTask::workOrderId);

    private final Map<Key, ExecutionTask> tasks = new HashMap<>();
    private final Map<Key, Long> versions = new HashMap<>();
    private final List<TechnicalNote> notes = new ArrayList<>();
    private final List<OutboundMessage> outbox = new ArrayList<>();
    private final Set<UUID> inbox = new HashSet<>();

    @Override
    public Optional<ExecutionTask> find(Long workOrderId, Phase phase) {
        Key key = new Key(workOrderId, phase);
        return Optional.ofNullable(tasks.get(key)).map(task -> copy(task, versions.get(key)));
    }

    @Override
    public List<ExecutionTask> findByWorkOrder(Long workOrderId) {
        return tasks.keySet().stream().filter(key -> key.workOrderId().equals(workOrderId))
            .map(key -> find(key.workOrderId(), key.phase()).orElseThrow())
            .sorted(Comparator.comparing(ExecutionTask::phase))
            .toList();
    }

    @Override
    public List<TechnicalNote> findNotes(Long workOrderId) {
        return List.copyOf(notes);
    }

    @Override
    public List<ExecutionTask> findQueue(Phase phase, TaskStatus status, int limit) {
        return tasks.values().stream()
            .filter(task -> task.phase() == phase && task.status() == status)
            .sorted(QUEUE_ORDER)
            .limit(limit)
            .toList();
    }

    @Override
    public int positionInQueue(ExecutionTask task) {
        return (int) tasks.values().stream()
            .filter(other -> other.phase() == task.phase() && other.status() == TaskStatus.QUEUED)
            .filter(other -> !other.workOrderId().equals(task.workOrderId()))
            .filter(other -> QUEUE_ORDER.compare(other, task) < 0)
            .count() + 1;
    }

    @Override
    public void commit(ExecutionChanges changes) {
        if (changes.processedMessage().filter(inbox::contains).isPresent()) {
            throw new DuplicateMessageException(changes.processedMessage().get().toString());
        }
        for (ExecutionTask task : changes.tasks()) {
            Long stored = versions.get(new Key(task.workOrderId(), task.phase()));
            if (!Objects.equals(stored, task.version())) {
                throw new ConcurrentTaskUpdateException("versão " + task.version() + " != " + stored);
            }
        }
        changes.tasks().forEach(task -> {
            Key key = new Key(task.workOrderId(), task.phase());
            versions.merge(key, 1L, Long::sum);
            tasks.put(key, task);
            notes.addAll(task.newNotes());
        });
        outbox.addAll(changes.messages());
        changes.processedMessage().ifPresent(inbox::add);
    }

    public List<OutboundMessage> outbox() {
        return List.copyOf(outbox);
    }

    public OutboundMessage lastMessage() {
        return outbox.get(outbox.size() - 1);
    }

    /** Cópia "como se lida do banco": sem eventos nem notas pendentes, com a versão gravada. */
    private static ExecutionTask copy(ExecutionTask task, Long version) {
        return ExecutionTask.rehydrate(task.workOrderId(), task.phase(), task.orderNumber(), task.status(),
            task.sagaId(), task.priority(), task.vehicle(), task.items(), task.assignee(), task.queuedAt(),
            task.startedAt(), task.finishedAt(), task.outcome(), version);
    }
}
