package br.com.oficina.execution.application.dto;

import br.com.oficina.execution.domain.model.ExecutionTask;
import br.com.oficina.execution.domain.model.VehicleInfo;
import br.com.oficina.execution.domain.model.WorkItem;
import java.time.OffsetDateTime;
import java.util.List;

/** Tarefa como a oficina a vê na fila e no detalhe da OS. */
public record TaskView(Long workOrderId,
                       String orderNumber,
                       String phase,
                       String status,
                       int priority,
                       VehicleInfo vehicle,
                       List<WorkItem> items,
                       String assignee,
                       OffsetDateTime queuedAt,
                       OffsetDateTime startedAt,
                       OffsetDateTime finishedAt,
                       String outcome) {

    public static TaskView from(ExecutionTask task) {
        return new TaskView(task.workOrderId(), task.orderNumber(), task.phase().name(), task.status().name(),
            task.priority(), task.vehicle(), task.items(), task.assignee(), task.queuedAt(), task.startedAt(),
            task.finishedAt(), task.outcome());
    }
}
