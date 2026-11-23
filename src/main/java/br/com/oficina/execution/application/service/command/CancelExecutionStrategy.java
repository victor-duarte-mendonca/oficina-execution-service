package br.com.oficina.execution.application.service.command;

import br.com.oficina.execution.application.messaging.ExecutionMessageFactory;
import br.com.oficina.execution.application.messaging.InboundMessage;
import br.com.oficina.execution.application.ports.in.HandleExecutionCommandUseCase.Result;
import br.com.oficina.execution.application.ports.out.ExecutionChanges;
import br.com.oficina.execution.application.ports.out.ExecutionTaskRepository;
import br.com.oficina.execution.application.service.TaskEvents;
import br.com.oficina.execution.domain.event.ExecutionEvent;
import br.com.oficina.execution.domain.model.ExecutionTask;
import br.com.oficina.execution.domain.model.Phase;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Compensação da Saga: cancela o reparo. Idempotente por construção — OS sem reparo, ou com reparo já
 * cancelado, responde "cancelado" do mesmo jeito; reparo concluído responde {@code alreadyFinished}.
 */
@ApplicationScoped
public class CancelExecutionStrategy implements SagaCommandStrategy {

    private final ExecutionTaskRepository repository;
    private final TaskEvents taskEvents;
    private final ExecutionMessageFactory messages;

    public CancelExecutionStrategy(ExecutionTaskRepository repository, TaskEvents taskEvents,
                                   ExecutionMessageFactory messages) {
        this.repository = repository;
        this.taskEvents = taskEvents;
        this.messages = messages;
    }

    @Override
    public String type() {
        return "CancelExecution";
    }

    @Override
    public Result apply(InboundMessage command, ExecutionChanges.Builder changes, OffsetDateTime now) {
        command.requiredSagaId();
        String reason = command.payload().text("reason");
        Optional<ExecutionTask> repair = repository.find(command.workOrderId(), Phase.REPAIR);
        if (repair.isEmpty()) {
            changes.publish(messages.replyTo(command, new ExecutionEvent.Cancelled(false), now));
            return Result.PROCESSED;
        }
        repair.get().cancel(reason, now);
        taskEvents.record(changes, repair.get(), command.messageId(), now);
        return Result.PROCESSED;
    }
}
