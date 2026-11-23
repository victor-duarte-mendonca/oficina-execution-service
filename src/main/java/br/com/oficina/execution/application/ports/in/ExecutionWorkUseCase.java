package br.com.oficina.execution.application.ports.in;

import br.com.oficina.execution.application.dto.ExecutionDetailsView;
import br.com.oficina.execution.application.dto.TaskView;
import br.com.oficina.execution.domain.model.Phase;
import br.com.oficina.execution.domain.model.TaskStatus;
import br.com.oficina.execution.domain.model.TechnicalNote;
import java.util.List;

/** Trabalho do mecânico pela API (spec 06 §3). */
public interface ExecutionWorkUseCase {

    List<TaskView> queue(Phase phase, TaskStatus status, int limit);

    ExecutionDetailsView details(Long workOrderId);

    TaskView start(Long workOrderId, Phase phase, String mechanic);

    TaskView addNote(Long workOrderId, Phase phase, String author, String text, TechnicalNote.Kind kind);

    TaskView finish(Long workOrderId, Phase phase, String summary);

    TaskView fail(Long workOrderId, String reason);
}
