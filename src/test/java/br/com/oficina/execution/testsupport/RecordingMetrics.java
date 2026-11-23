package br.com.oficina.execution.testsupport;

import br.com.oficina.execution.application.ports.out.ExecutionMetricsPort;
import java.util.ArrayList;
import java.util.List;

/** Métricas registradas em lista, para os testes afirmarem o que foi contado. */
public final class RecordingMetrics implements ExecutionMetricsPort {

    private final List<String> published = new ArrayList<>();
    private final List<String> handled = new ArrayList<>();

    @Override
    public void eventPublished(String type) {
        published.add(type);
    }

    @Override
    public void commandHandled(String type, String outcome) {
        handled.add(type + ":" + outcome);
    }

    public List<String> published() {
        return List.copyOf(published);
    }

    public List<String> handled() {
        return List.copyOf(handled);
    }
}
