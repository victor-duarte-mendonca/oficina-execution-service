package br.com.oficina.execution.interfaces.rest;

import br.com.oficina.execution.application.dto.ExecutionDetailsView;
import br.com.oficina.execution.application.dto.TaskView;
import br.com.oficina.execution.application.ports.in.ExecutionWorkUseCase;
import br.com.oficina.execution.domain.model.Phase;
import br.com.oficina.execution.domain.model.TaskStatus;
import br.com.oficina.execution.domain.model.TechnicalNote;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Fila de execução e trabalho do mecânico (spec 06 §3). O responsável pela tarefa é sempre o usuário do
 * token — nunca um campo do corpo — para o histórico não aceitar autoria declarada.
 */
@Path("/admin/executions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"ADMIN", "MECHANIC"})
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Execução", description = "Fila de diagnóstico e reparo das OS")
public class ExecutionResource {

    private final ExecutionWorkUseCase work;
    private final SecurityIdentity identity;

    public ExecutionResource(ExecutionWorkUseCase work, SecurityIdentity identity) {
        this.work = work;
        this.identity = identity;
    }

    public record NoteRequest(@NotBlank @Size(max = TechnicalNote.MAX_LENGTH) String text,
                              TechnicalNote.Kind kind) {
    }

    public record FinishRequest(@Size(max = 1000) String summary) {
    }

    public record FailRequest(@NotBlank @Size(max = 300) String reason) {
    }

    @GET
    @Operation(summary = "Fila de execução",
               description = "Ordenada por prioridade e antiguidade. Padrão: reparos aguardando início.")
    public List<TaskView> queue(@QueryParam("phase") @DefaultValue("REPAIR") Phase phase,
                                @QueryParam("status") @DefaultValue("QUEUED") TaskStatus status,
                                @QueryParam("limit") @DefaultValue("20") int limit) {
        return work.queue(phase, status, limit);
    }

    @GET
    @Path("/{workOrderId}")
    @Operation(summary = "Tarefas e notas técnicas de uma OS")
    public ExecutionDetailsView details(@PathParam("workOrderId") Long workOrderId) {
        return work.details(workOrderId);
    }

    @POST
    @Path("/{workOrderId}/start")
    @Operation(summary = "Iniciar a tarefa", description = "No reparo, avisa a Saga (ExecutionStarted).")
    public TaskView start(@PathParam("workOrderId") Long workOrderId,
                          @QueryParam("phase") @DefaultValue("REPAIR") Phase phase) {
        return work.start(workOrderId, phase, mechanic());
    }

    @POST
    @Path("/{workOrderId}/notes")
    @Operation(summary = "Registrar nota técnica", description = "Vai ao histórico da OS como ExecutionProgress.")
    public TaskView addNote(@PathParam("workOrderId") Long workOrderId,
                            @QueryParam("phase") @DefaultValue("REPAIR") Phase phase,
                            @Valid @NotNull NoteRequest request) {
        return work.addNote(workOrderId, phase, mechanic(), request.text(), request.kind());
    }

    @POST
    @Path("/{workOrderId}/finish")
    @Operation(summary = "Concluir a tarefa", description = "No reparo, conclui a Saga (ExecutionFinished).")
    public TaskView finish(@PathParam("workOrderId") Long workOrderId,
                           @QueryParam("phase") @DefaultValue("REPAIR") Phase phase,
                           @Valid FinishRequest request) {
        return work.finish(workOrderId, phase, request == null ? null : request.summary());
    }

    @POST
    @Path("/{workOrderId}/fail")
    @Operation(summary = "Registrar falha do reparo",
               description = "Dispara a compensação da Saga: estorno do pagamento e cancelamento da OS.")
    public TaskView fail(@PathParam("workOrderId") Long workOrderId, @Valid @NotNull FailRequest request) {
        return work.fail(workOrderId, request.reason());
    }

    private String mechanic() {
        return identity.getPrincipal().getName();
    }
}
