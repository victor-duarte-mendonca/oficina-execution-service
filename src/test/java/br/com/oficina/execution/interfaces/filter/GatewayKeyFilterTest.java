package br.com.oficina.execution.interfaces.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** {@code /admin/*} só pelo gateway: sem a chave certa, 403; health e métricas ficam livres. */
class GatewayKeyFilterTest {

    private static final String KEY = "chave-do-gateway";

    @Test
    void adminWithoutKey_shouldBeForbidden() {
        ContainerRequestContext request = request("admin/executions", null);

        new GatewayKeyFilter(Optional.of(KEY)).filter(request);

        ArgumentCaptor<Response> response = ArgumentCaptor.forClass(Response.class);
        verify(request).abortWith(response.capture());
        assertThat(response.getValue().getStatus()).isEqualTo(403);
    }

    @Test
    void adminWithWrongKey_shouldBeForbidden() {
        ContainerRequestContext request = request("/admin", "outra-chave");

        new GatewayKeyFilter(Optional.of(KEY)).filter(request);

        verify(request).abortWith(any());
    }

    @Test
    void adminWithTheGatewayKey_shouldPass() {
        ContainerRequestContext request = request("/admin/executions/1", KEY);

        new GatewayKeyFilter(Optional.of(KEY)).filter(request);

        verify(request, never()).abortWith(any());
    }

    @Test
    void healthAndMetrics_shouldNotRequireTheKey() {
        ContainerRequestContext health = request("/q/health/ready", null);
        ContainerRequestContext lookalike = request("/administrador", null);

        new GatewayKeyFilter(Optional.of(KEY)).filter(health);
        new GatewayKeyFilter(Optional.of(KEY)).filter(lookalike);

        verify(health, never()).abortWith(any());
        verify(lookalike, never()).abortWith(any());
    }

    @Test
    void blankKey_shouldDisableTheFilter() {
        ContainerRequestContext request = request("/admin/executions", null);

        new GatewayKeyFilter(Optional.of(" ")).filter(request);
        new GatewayKeyFilter(Optional.empty()).filter(request);

        verify(request, never()).abortWith(any());
    }

    private static ContainerRequestContext request(String path, String key) {
        ContainerRequestContext request = mock(ContainerRequestContext.class);
        UriInfo uri = mock(UriInfo.class);
        when(uri.getPath()).thenReturn(path);
        when(request.getUriInfo()).thenReturn(uri);
        when(request.getHeaderString(GatewayKeyFilter.HEADER)).thenReturn(key);
        return request;
    }
}
