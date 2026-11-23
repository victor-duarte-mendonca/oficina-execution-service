package br.com.oficina.execution.infrastructure.observability;

import br.com.oficina.execution.application.ports.out.ExecutionMetricsPort;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;

/** Métricas em {@code /q/metrics}: eventos publicados (falhas de execução incluídas) e comandos tratados. */
@ApplicationScoped
public class MicrometerExecutionMetrics implements ExecutionMetricsPort {

    private final MeterRegistry registry;

    public MicrometerExecutionMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void eventPublished(String type) {
        registry.counter("execution_events_total", "type", type).increment();
        if ("ExecutionFailed".equals(type)) {
            registry.counter("execution_failures_total").increment();
        }
    }

    @Override
    public void commandHandled(String type, String outcome) {
        registry.counter("execution_commands_total", "type", type, "outcome", outcome).increment();
    }
}
