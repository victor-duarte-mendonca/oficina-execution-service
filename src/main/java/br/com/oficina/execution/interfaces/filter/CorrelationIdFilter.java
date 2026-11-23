package br.com.oficina.execution.interfaces.filter;

import br.com.oficina.execution.infrastructure.observability.RequestContext;
import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Correlação de requisições (mesma regra do OS): {@code X-Correlation-Id} do cliente, senão o
 * {@code requestId} do API Gateway, senão um UUID novo. Vai para o MDC (logs JSON), para as mensagens
 * publicadas e para a resposta. Valores fora do formato seguro são descartados (sem injeção em log).
 */
@Provider
@Priority(Priorities.AUTHENTICATION - 200)
public class CorrelationIdFilter implements ContainerRequestFilter, ContainerResponseFilter {

    static final String HEADER = "X-Correlation-Id";
    static final String GATEWAY_HEADER = "X-Amzn-Request-Id";
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._=-]{1,128}");

    @Override
    public void filter(ContainerRequestContext request) {
        String correlationId = Stream.of(request.getHeaderString(HEADER), request.getHeaderString(GATEWAY_HEADER))
            .map(Optional::ofNullable)
            .flatMap(Optional::stream)
            .filter(value -> SAFE.matcher(value).matches())
            .findFirst()
            .orElseGet(() -> UUID.randomUUID().toString());
        RequestContext.putCorrelationId(correlationId);
    }

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        RequestContext.correlationId().ifPresent(id -> response.getHeaders().putSingle(HEADER, id));
        RequestContext.clearCorrelationId();
    }
}
