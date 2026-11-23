package br.com.oficina.execution.application.ports.out;

import br.com.oficina.execution.domain.model.ExecutionTask;
import br.com.oficina.execution.domain.model.Phase;
import br.com.oficina.execution.domain.model.TaskStatus;
import br.com.oficina.execution.domain.model.TechnicalNote;
import java.util.List;
import java.util.Optional;

/** Leitura das tarefas. A escrita é atômica e passa pelo {@link ExecutionUnitOfWork}. */
public interface ExecutionTaskRepository {

    Optional<ExecutionTask> find(Long workOrderId, Phase phase);

    /** Tarefas da OS nas duas fases. */
    List<ExecutionTask> findByWorkOrder(Long workOrderId);

    List<TechnicalNote> findNotes(Long workOrderId);

    /** Fila ordenada por prioridade e antiguidade (a mais urgente primeiro). */
    List<ExecutionTask> findQueue(Phase phase, TaskStatus status, int limit);

    /** Posição (1 = próxima) que a tarefa ocupa, ou ocuparia, entre as {@code QUEUED} da sua fase. */
    int positionInQueue(ExecutionTask task);
}
