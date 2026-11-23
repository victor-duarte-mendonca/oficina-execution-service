package br.com.oficina.execution.domain.model;

import br.com.oficina.execution.domain.event.ExecutionEvent;
import br.com.oficina.execution.domain.exception.ExecutionRuleViolation;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Agregado raiz do Execution Service: o trabalho de uma OS numa fase (spec 06 §2).
 *
 * <pre>
 * DIAGNOSIS: QUEUED → IN_PROGRESS → FINISHED                (informativa)
 * REPAIR:    QUEUED → IN_PROGRESS → FINISHED | FAILED       (responde à Saga)
 *            QUEUED | IN_PROGRESS → CANCELLED               (compensação)
 * </pre>
 *
 * <p>Invariantes: uma tarefa por (OS, fase); só inicia o que está na fila; só conclui ou falha o que
 * foi iniciado; tarefa encerrada não recebe trabalho. Os comandos vindos da Saga são <b>idempotentes</b>
 * — a mesma ordem repetida devolve o mesmo resultado, nunca um erro — porque o SQS entrega pelo menos
 * uma vez.</p>
 *
 * <p>Cada mudança registra o evento correspondente ({@link #pullEvents()}): a aplicação grava o estado
 * e as mensagens de saída na mesma operação atômica, sem o agregado conhecer mensageria.</p>
 */
public final class ExecutionTask {

    private final Long workOrderId;
    private final Phase phase;
    private String orderNumber;
    private TaskStatus status;
    private UUID sagaId;
    private int priority;
    private VehicleInfo vehicle;
    private List<WorkItem> items;
    private String assignee;
    private OffsetDateTime queuedAt;
    private OffsetDateTime startedAt;
    private OffsetDateTime finishedAt;
    private String outcome;
    private Long version;
    private final List<TechnicalNote> newNotes = new ArrayList<>();
    private final List<ExecutionEvent> events = new ArrayList<>();

    private ExecutionTask(Long workOrderId, Phase phase) {
        this.workOrderId = Objects.requireNonNull(workOrderId);
        this.phase = Objects.requireNonNull(phase);
    }

    /** Reparo pago: entra na fila e responde {@code ExecutionEnqueued} à Saga. */
    public static ExecutionTask queueRepair(Long workOrderId, String orderNumber, UUID sagaId, VehicleInfo vehicle,
                                            List<WorkItem> items, int priority, OffsetDateTime now) {
        ExecutionTask task = new ExecutionTask(workOrderId, Phase.REPAIR);
        task.orderNumber = orderNumber;
        task.enqueue(Objects.requireNonNull(sagaId, "Reparo exige a Saga de origem"), vehicle, items, priority, now);
        return task;
    }

    /** Diagnóstico aberto no OS: a tarefa aparece na fila para o mecânico registrar o que encontrar. */
    public static ExecutionTask openDiagnosis(Long workOrderId, String orderNumber, VehicleInfo vehicle,
                                              OffsetDateTime now) {
        ExecutionTask task = new ExecutionTask(workOrderId, Phase.DIAGNOSIS);
        task.orderNumber = orderNumber;
        task.vehicle = Objects.requireNonNull(vehicle);
        task.items = List.of();
        task.resetToQueue(now);
        return task;
    }

    /** Reconstrói a tarefa persistida (uso exclusivo do adapter). */
    public static ExecutionTask rehydrate(Long workOrderId, Phase phase, String orderNumber, TaskStatus status,
                                          UUID sagaId, int priority, VehicleInfo vehicle, List<WorkItem> items,
                                          String assignee, OffsetDateTime queuedAt, OffsetDateTime startedAt,
                                          OffsetDateTime finishedAt, String outcome, Long version) {
        ExecutionTask task = new ExecutionTask(workOrderId, phase);
        task.orderNumber = orderNumber;
        task.status = status;
        task.sagaId = sagaId;
        task.priority = priority;
        task.vehicle = vehicle;
        task.items = List.copyOf(items);
        task.assignee = assignee;
        task.queuedAt = queuedAt;
        task.startedAt = startedAt;
        task.finishedAt = finishedAt;
        task.outcome = outcome;
        task.version = version;
        return task;
    }

    // ===== Comandos da Saga (idempotentes) =====

    /**
     * {@code EnqueueExecution} para uma OS que já tem tarefa de reparo: repetição da mesma Saga confirma
     * de novo; uma Saga nova reabre uma tarefa cancelada ou que falhou; reparo concluído não volta à fila.
     */
    public void requeueForSaga(UUID newSagaId, VehicleInfo newVehicle, List<WorkItem> newItems, int newPriority,
                               OffsetDateTime now) {
        requirePhase(Phase.REPAIR);
        switch (status) {
            case QUEUED, IN_PROGRESS -> {
                if (!newSagaId.equals(sagaId)) {
                    throw new ExecutionRuleViolation("A OS " + orderNumber + " já tem reparo em andamento de outra Saga");
                }
                events.add(new ExecutionEvent.Enqueued());
            }
            case FINISHED -> throw new ExecutionRuleViolation("O reparo da OS " + orderNumber + " já foi concluído");
            case FAILED, CANCELLED -> enqueue(newSagaId, newVehicle, newItems, newPriority, now);
        }
    }

    /** {@code DiagnosisStarted} repetido (a OS voltou ao diagnóstico): reabre se já tinha sido encerrado. */
    public void reopenDiagnosis(OffsetDateTime now) {
        requirePhase(Phase.DIAGNOSIS);
        if (status.isTerminal()) {
            resetToQueue(now);
        }
    }

    /**
     * {@code CancelExecution}: compensação da Saga. Em tarefa concluída não há o que cancelar — a resposta
     * diz isso ({@code alreadyFinished}) e a Saga decide; nos demais casos o resultado é "cancelada".
     */
    public void cancel(String reason, OffsetDateTime now) {
        switch (status) {
            case QUEUED, IN_PROGRESS -> {
                status = TaskStatus.CANCELLED;
                finishedAt = now;
                outcome = reason;
                events.add(new ExecutionEvent.Cancelled(false));
            }
            case FINISHED -> events.add(new ExecutionEvent.Cancelled(true));
            case FAILED, CANCELLED -> events.add(new ExecutionEvent.Cancelled(false));
        }
    }

    // ===== Trabalho do mecânico =====

    public void start(String mechanic, OffsetDateTime now) {
        requireStatus(TaskStatus.QUEUED, "iniciar");
        if (mechanic == null || mechanic.isBlank()) {
            throw new ExecutionRuleViolation("Início sem mecânico responsável");
        }
        status = TaskStatus.IN_PROGRESS;
        assignee = mechanic;
        startedAt = now;
        if (phase.answersToSaga()) {
            events.add(new ExecutionEvent.Started(mechanic));
        }
    }

    public TechnicalNote addNote(String author, String text, TechnicalNote.Kind kind, OffsetDateTime now) {
        if (status.isTerminal()) {
            throw new ExecutionRuleViolation("Tarefa encerrada (" + status + ") não recebe notas");
        }
        TechnicalNote note = new TechnicalNote(UUID.randomUUID(), phase, author, text,
            kind == null ? TechnicalNote.Kind.OBSERVATION : kind, now);
        newNotes.add(note);
        events.add(new ExecutionEvent.Progress(phase, text));
        return note;
    }

    public void finish(String summary, OffsetDateTime now) {
        requireStatus(TaskStatus.IN_PROGRESS, "concluir");
        status = TaskStatus.FINISHED;
        finishedAt = now;
        outcome = summary == null ? "" : summary;
        if (phase.answersToSaga()) {
            events.add(new ExecutionEvent.Finished(outcome));
        }
    }

    /** Falha do reparo (peça indisponível, defeito sem conserto): a Saga estorna o cliente. */
    public void fail(String reason, OffsetDateTime now) {
        requirePhase(Phase.REPAIR);
        requireStatus(TaskStatus.IN_PROGRESS, "registrar falha em");
        if (reason == null || reason.isBlank()) {
            throw new ExecutionRuleViolation("A falha precisa de um motivo: ele vai para o cliente e para o estorno");
        }
        status = TaskStatus.FAILED;
        finishedAt = now;
        outcome = reason;
        events.add(new ExecutionEvent.Failed(reason));
    }

    // ===== Eventos e notas pendentes de gravação =====

    /** Eventos desde a última leitura (e os esquece): cada um vira uma mensagem para o OS. */
    public List<ExecutionEvent> pullEvents() {
        List<ExecutionEvent> pending = List.copyOf(events);
        events.clear();
        return pending;
    }

    /** Notas criadas desde o carregamento, a gravar junto com a tarefa. */
    public List<TechnicalNote> newNotes() {
        return List.copyOf(newNotes);
    }

    // ===== Internos =====

    private void enqueue(UUID newSagaId, VehicleInfo newVehicle, List<WorkItem> newItems, int newPriority,
                         OffsetDateTime now) {
        if (newItems == null || newItems.isEmpty()) {
            throw new ExecutionRuleViolation("Reparo sem itens a executar");
        }
        if (newPriority < 0) {
            throw new ExecutionRuleViolation("Prioridade não pode ser negativa");
        }
        sagaId = newSagaId;
        vehicle = Objects.requireNonNull(newVehicle);
        items = List.copyOf(newItems);
        priority = newPriority;
        resetToQueue(now);
        events.add(new ExecutionEvent.Enqueued());
    }

    private void resetToQueue(OffsetDateTime now) {
        status = TaskStatus.QUEUED;
        queuedAt = now;
        startedAt = null;
        finishedAt = null;
        assignee = null;
        outcome = null;
    }

    private void requireStatus(TaskStatus expected, String action) {
        if (status != expected) {
            throw new ExecutionRuleViolation("Não é possível " + action + " a tarefa de " + phase
                + " da OS " + orderNumber + ": status atual " + status + ", esperado " + expected);
        }
    }

    private void requirePhase(Phase expected) {
        if (phase != expected) {
            throw new ExecutionRuleViolation("Operação exclusiva da fase " + expected);
        }
    }

    // ===== Leitura =====

    public Long workOrderId() { return workOrderId; }
    public Phase phase() { return phase; }
    public String orderNumber() { return orderNumber; }
    public TaskStatus status() { return status; }
    public UUID sagaId() { return sagaId; }
    public int priority() { return priority; }
    public VehicleInfo vehicle() { return vehicle; }
    public List<WorkItem> items() { return items; }
    public String assignee() { return assignee; }
    public OffsetDateTime queuedAt() { return queuedAt; }
    public OffsetDateTime startedAt() { return startedAt; }
    public OffsetDateTime finishedAt() { return finishedAt; }
    public String outcome() { return outcome; }
    /** {@code null} para tarefa ainda não gravada (a gravação exige que ela não exista). */
    public Long version() { return version; }
}
