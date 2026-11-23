package br.com.oficina.execution.interfaces.rest;

import br.com.oficina.execution.application.ports.out.ExecutionUnitOfWork.ConcurrentTaskUpdateException;
import br.com.oficina.execution.domain.exception.ExecutionRuleViolation;
import br.com.oficina.execution.domain.exception.TaskNotFoundException;
import br.com.oficina.execution.infrastructure.observability.RequestContext;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jboss.logging.Logger;

/** Erros em JSON, sem stacktrace para o cliente; o inesperado é logado com a correlação para busca. */
@Provider
public class ErrorMapper implements ExceptionMapper<Throwable> {

    private static final Logger LOG = Logger.getLogger(ErrorMapper.class);
    private static final int UNPROCESSABLE_ENTITY = 422;

    @Override
    public Response toResponse(Throwable exception) {
        return switch (exception) {
            case TaskNotFoundException e -> error(Response.Status.NOT_FOUND.getStatusCode(), e.getMessage());
            case ExecutionRuleViolation e -> error(UNPROCESSABLE_ENTITY, e.getMessage());
            case ConcurrentTaskUpdateException e -> error(Response.Status.CONFLICT.getStatusCode(),
                "A tarefa foi alterada ao mesmo tempo por outra pessoa. Recarregue e tente de novo.");
            case WebApplicationException e -> e.getResponse();
            default -> {
                LOG.errorf(exception, "Erro inesperado (correlationId=%s)", RequestContext.correlationId().orElse("-"));
                yield error(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(),
                    "Erro interno. Informe o correlationId ao suporte.");
            }
        };
    }

    private static Response error(int status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", OffsetDateTime.now().toString());
        body.put("status", status);
        body.put("message", message);
        RequestContext.correlationId().ifPresent(id -> body.put("correlationId", id));
        return Response.status(status).type(MediaType.APPLICATION_JSON).entity(body).build();
    }
}
