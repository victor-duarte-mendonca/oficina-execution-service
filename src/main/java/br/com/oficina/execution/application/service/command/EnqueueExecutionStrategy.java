package br.com.oficina.execution.application.service.command;

import br.com.oficina.execution.application.messaging.ExecutionMessageFactory;
import br.com.oficina.execution.application.messaging.InboundMessage;
import br.com.oficina.execution.application.ports.in.HandleExecutionCommandUseCase.Result;
import br.com.oficina.execution.application.ports.out.ExecutionChanges;
import br.com.oficina.execution.application.ports.out.ExecutionTaskRepository;
import br.com.oficina.execution.application.service.TaskEvents;
import br.com.oficina.execution.domain.event.ExecutionEvent;
import br.com.oficina.execution.domain.exception.ExecutionRuleViolation;
import br.com.oficina.execution.domain.model.ExecutionTask;
import br.com.oficina.execution.domain.model.Phase;
import br.com.oficina.execution.domain.model.VehicleInfo;
import br.com.oficina.execution.domain.model.WorkItem;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Passo 5 da Saga: pagamento confirmado, a OS entra na fila de reparo. A resposta é
 * {@code ExecutionEnqueued} com a posição, ou {@code ExecutionEnqueueFailed} se a regra de negócio
 * recusar (ex.: reparo já concluído) — nesse caso a Saga estorna o cliente.
 */
@ApplicationScoped
public class EnqueueExecutionStrategy implements SagaCommandStrategy {

    private static final int DEFAULT_PRIORITY = 0;

    private final ExecutionTaskRepository repository;
    private final TaskEvents taskEvents;
    private final ExecutionMessageFactory messages;

    public EnqueueExecutionStrategy(ExecutionTaskRepository repository, TaskEvents taskEvents,
                                    ExecutionMessageFactory messages) {
        this.repository = repository;
        this.taskEvents = taskEvents;
        this.messages = messages;
    }

    @Override
    public String type() {
        return "EnqueueExecution";
    }

    @Override
    public Result apply(InboundMessage command, ExecutionChanges.Builder changes, OffsetDateTime now) {
        UUID sagaId = command.requiredSagaId();
        VehicleInfo vehicle;
        List<WorkItem> items;
        int priority = command.payload().integerOr("priority", DEFAULT_PRIORITY);
        try {
            vehicle = CommandPayloads.vehicle(command.payload());
            items = CommandPayloads.items(command.payload());
            ExecutionTask task = repository.find(command.workOrderId(), Phase.REPAIR)
                .map(existing -> {
                    existing.requeueForSaga(sagaId, vehicle, items, priority, now);
                    return existing;
                })
                .orElseGet(() -> ExecutionTask.queueRepair(command.workOrderId(), command.orderNumber(), sagaId,
                    vehicle, items, priority, now));
            taskEvents.record(changes, task, command.messageId(), now);
            return Result.PROCESSED;
        } catch (ExecutionRuleViolation refused) {
            changes.publish(messages.replyTo(command, new ExecutionEvent.EnqueueRefused(refused.getMessage()), now));
            return Result.REFUSED;
        }
    }
}
