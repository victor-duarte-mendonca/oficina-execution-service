package br.com.oficina.execution.application.service;

import br.com.oficina.execution.application.messaging.InboundMessage;
import br.com.oficina.execution.application.ports.in.HandleExecutionCommandUseCase;
import br.com.oficina.execution.application.ports.out.ExecutionChanges;
import br.com.oficina.execution.application.ports.out.ExecutionMetricsPort;
import br.com.oficina.execution.application.ports.out.ExecutionUnitOfWork;
import br.com.oficina.execution.application.ports.out.ExecutionUnitOfWork.DuplicateMessageException;
import br.com.oficina.execution.application.service.command.SagaCommandStrategy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Clock;
import java.util.Collection;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jboss.logging.Logger;

/**
 * Despacha cada comando do OS para a sua {@link SagaCommandStrategy} e grava o resultado de uma vez, com
 * o registro da mensagem no inbox. Mensagem repetida é detectada na gravação (o inbox é condicional) e
 * nada muda; tipo desconhecido é registrado e confirmado sem efeito.
 */
@ApplicationScoped
public class ExecutionCommandHandler implements HandleExecutionCommandUseCase {

    private static final Logger LOG = Logger.getLogger(ExecutionCommandHandler.class);

    private final Map<String, SagaCommandStrategy> strategies;
    private final ExecutionUnitOfWork unitOfWork;
    private final ExecutionMetricsPort metrics;
    private final Clock clock;

    @Inject
    public ExecutionCommandHandler(Instance<SagaCommandStrategy> strategies, ExecutionUnitOfWork unitOfWork,
                                   ExecutionMetricsPort metrics, Clock clock) {
        this(strategies.stream().toList(), unitOfWork, metrics, clock);
    }

    public ExecutionCommandHandler(Collection<SagaCommandStrategy> strategies, ExecutionUnitOfWork unitOfWork,
                                   ExecutionMetricsPort metrics, Clock clock) {
        this.strategies = strategies.stream()
            .collect(Collectors.toUnmodifiableMap(SagaCommandStrategy::type, Function.identity()));
        this.unitOfWork = unitOfWork;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    public Result handle(InboundMessage command) {
        ExecutionChanges.Builder changes = ExecutionChanges.builder().processed(command.messageId());
        SagaCommandStrategy strategy = strategies.get(command.type());
        Result result = strategy == null ? Result.SKIPPED : strategy.apply(command, changes, OffsetDateTime.now(clock));
        if (result == Result.SKIPPED) {
            LOG.warnf("Tipo de comando desconhecido: %s (mensagem %s)", command.type(), command.messageId());
        }
        ExecutionChanges batch = changes.build();
        try {
            unitOfWork.commit(batch);
        } catch (DuplicateMessageException duplicate) {
            result = Result.DUPLICATE;
        }
        metrics.commandHandled(command.type(), result.name().toLowerCase(Locale.ROOT));
        if (result != Result.DUPLICATE) {
            batch.messages().forEach(message -> metrics.eventPublished(message.type()));
        }
        LOG.infof("Comando %s da OS %s: %s", command.type(), command.workOrderId(), result);
        return result;
    }
}
