package br.com.oficina.execution.application.service.command;

import br.com.oficina.execution.application.messaging.InboundMessage;
import br.com.oficina.execution.application.ports.in.HandleExecutionCommandUseCase.Result;
import br.com.oficina.execution.application.ports.out.ExecutionChanges;
import br.com.oficina.execution.application.ports.out.ExecutionTaskRepository;
import br.com.oficina.execution.application.service.TaskEvents;
import br.com.oficina.execution.domain.model.ExecutionTask;
import br.com.oficina.execution.domain.model.Phase;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.OffsetDateTime;

/**
 * Informativo do OS: o diagnóstico começou. Abre (ou reabre) a tarefa de diagnóstico para o mecânico
 * registrar o que encontrar. Não é passo da Saga: não há resposta.
 */
@ApplicationScoped
public class DiagnosisStartedStrategy implements SagaCommandStrategy {

    private final ExecutionTaskRepository repository;
    private final TaskEvents taskEvents;

    public DiagnosisStartedStrategy(ExecutionTaskRepository repository, TaskEvents taskEvents) {
        this.repository = repository;
        this.taskEvents = taskEvents;
    }

    @Override
    public String type() {
        return "DiagnosisStarted";
    }

    @Override
    public Result apply(InboundMessage command, ExecutionChanges.Builder changes, OffsetDateTime now) {
        ExecutionTask diagnosis = repository.find(command.workOrderId(), Phase.DIAGNOSIS)
            .map(existing -> {
                existing.reopenDiagnosis(now);
                return existing;
            })
            .orElseGet(() -> ExecutionTask.openDiagnosis(command.workOrderId(), command.orderNumber(),
                CommandPayloads.vehicle(command.payload()), now));
        taskEvents.record(changes, diagnosis, command.messageId(), now);
        return Result.PROCESSED;
    }
}
