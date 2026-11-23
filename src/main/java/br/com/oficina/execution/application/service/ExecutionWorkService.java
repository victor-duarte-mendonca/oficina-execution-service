package br.com.oficina.execution.application.service;

import br.com.oficina.execution.application.dto.ExecutionDetailsView;
import br.com.oficina.execution.application.dto.ExecutionDetailsView.NoteView;
import br.com.oficina.execution.application.dto.TaskView;
import br.com.oficina.execution.application.ports.in.ExecutionWorkUseCase;
import br.com.oficina.execution.application.ports.out.ExecutionChanges;
import br.com.oficina.execution.application.ports.out.ExecutionMetricsPort;
import br.com.oficina.execution.application.ports.out.ExecutionTaskRepository;
import br.com.oficina.execution.application.ports.out.ExecutionUnitOfWork;
import br.com.oficina.execution.domain.exception.TaskNotFoundException;
import br.com.oficina.execution.domain.model.ExecutionTask;
import br.com.oficina.execution.domain.model.Phase;
import br.com.oficina.execution.domain.model.TaskStatus;
import br.com.oficina.execution.domain.model.TechnicalNote;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Trabalho do mecânico: iniciar, anotar, concluir e registrar falha. Toda alteração grava a tarefa e a
 * mensagem para o OS juntas — o mecânico concluir o reparo e o OS saber disso são uma coisa só.
 */
@ApplicationScoped
public class ExecutionWorkService implements ExecutionWorkUseCase {

    private static final int MAX_QUEUE_PAGE = 100;

    private final ExecutionTaskRepository repository;
    private final ExecutionUnitOfWork unitOfWork;
    private final TaskEvents taskEvents;
    private final ExecutionMetricsPort metrics;
    private final Clock clock;

    public ExecutionWorkService(ExecutionTaskRepository repository, ExecutionUnitOfWork unitOfWork,
                                TaskEvents taskEvents, ExecutionMetricsPort metrics, Clock clock) {
        this.repository = repository;
        this.unitOfWork = unitOfWork;
        this.taskEvents = taskEvents;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    public List<TaskView> queue(Phase phase, TaskStatus status, int limit) {
        return repository.findQueue(phase, status, Math.clamp(limit, 1, MAX_QUEUE_PAGE)).stream()
            .map(TaskView::from)
            .toList();
    }

    @Override
    public ExecutionDetailsView details(Long workOrderId) {
        List<TaskView> tasks = repository.findByWorkOrder(workOrderId).stream().map(TaskView::from).toList();
        if (tasks.isEmpty()) {
            throw new TaskNotFoundException(workOrderId, Phase.REPAIR);
        }
        return new ExecutionDetailsView(workOrderId, tasks,
            repository.findNotes(workOrderId).stream().map(NoteView::from).toList());
    }

    @Override
    public TaskView start(Long workOrderId, Phase phase, String mechanic) {
        return change(workOrderId, phase, (task, now) -> task.start(mechanic, now));
    }

    @Override
    public TaskView addNote(Long workOrderId, Phase phase, String author, String text, TechnicalNote.Kind kind) {
        return change(workOrderId, phase, (task, now) -> task.addNote(author, text, kind, now));
    }

    @Override
    public TaskView finish(Long workOrderId, Phase phase, String summary) {
        return change(workOrderId, phase, (task, now) -> task.finish(summary, now));
    }

    @Override
    public TaskView fail(Long workOrderId, String reason) {
        return change(workOrderId, Phase.REPAIR, (task, now) -> task.fail(reason, now));
    }

    private TaskView change(Long workOrderId, Phase phase, BiConsumer<ExecutionTask, OffsetDateTime> operation) {
        ExecutionTask task = repository.find(workOrderId, phase)
            .orElseThrow(() -> new TaskNotFoundException(workOrderId, phase));
        OffsetDateTime now = OffsetDateTime.now(clock);
        operation.accept(task, now);
        ExecutionChanges changes = taskEvents.record(ExecutionChanges.builder(), task, null, now).build();
        unitOfWork.commit(changes);
        changes.messages().forEach(message -> metrics.eventPublished(message.type()));
        return TaskView.from(task);
    }
}
