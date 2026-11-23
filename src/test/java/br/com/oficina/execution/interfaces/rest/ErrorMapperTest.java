package br.com.oficina.execution.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.oficina.execution.application.ports.out.ExecutionUnitOfWork.ConcurrentTaskUpdateException;
import br.com.oficina.execution.domain.exception.ExecutionRuleViolation;
import br.com.oficina.execution.domain.exception.TaskNotFoundException;
import br.com.oficina.execution.domain.model.Phase;
import br.com.oficina.execution.infrastructure.observability.RequestContext;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Cada erro com o status certo e sem detalhe interno; o inesperado vira 500 genérico com a correlação. */
class ErrorMapperTest {

    private final ErrorMapper mapper = new ErrorMapper();

    @AfterEach
    void clear() {
        RequestContext.clearCorrelationId();
    }

    @Test
    void domainErrors_shouldMapToTheirStatus() {
        assertThat(mapper.toResponse(new TaskNotFoundException(1L, Phase.REPAIR)).getStatus()).isEqualTo(404);
        assertThat(mapper.toResponse(new ExecutionRuleViolation("x")).getStatus()).isEqualTo(422);
        assertThat(mapper.toResponse(new ConcurrentTaskUpdateException("v1")).getStatus()).isEqualTo(409);
    }

    @Test
    void webApplicationException_shouldKeepItsStatus() {
        Response unauthorized = mapper.toResponse(new NotAuthorizedException("Bearer"));
        assertThat(unauthorized.getStatus()).isEqualTo(401);
    }

    @Test
    void unexpectedError_shouldHideTheCauseAndCarryTheCorrelation() {
        RequestContext.putCorrelationId("req-500");

        Response response = mapper.toResponse(new IllegalStateException("senha=123 no stack"));

        assertThat(response.getStatus()).isEqualTo(500);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getEntity();
        assertThat(body.get("message").toString()).doesNotContain("senha");
        assertThat(body).containsEntry("correlationId", "req-500");
    }
}
