package br.com.oficina.execution.infrastructure.messaging;

import br.com.oficina.execution.infrastructure.configuration.ExecutionConfig;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.Scheduled.ConcurrentExecution;
import io.quarkus.scheduler.ScheduledExecution;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Singleton;

/**
 * Relay do outbox e consumo dos comandos. Pulados com a mensageria desligada; nunca se sobrepõem. Começam
 * alguns segundos depois da subida ({@code app.messaging.start-delay}): sem isso, em dev, o relay consultava
 * a tabela antes de o {@code TableInitializer} terminar de criá-la.
 */
@ApplicationScoped
public class ExecutionJobs {

    private final OutboxPublisher publisher;
    private final CommandConsumer consumer;
    private final int batch;

    public ExecutionJobs(OutboxPublisher publisher, CommandConsumer consumer, ExecutionConfig config) {
        this.publisher = publisher;
        this.consumer = consumer;
        this.batch = config.messaging().relayBatchSize();
    }

    @Scheduled(identity = "outbox-relay", every = "${app.messaging.relay-interval:1s}", delayed = "${app.messaging.start-delay:5s}",
        concurrentExecution = ConcurrentExecution.SKIP, skipExecutionIf = MessagingDisabled.class)
    void relayOutbox() {
        int published;
        do {
            published = publisher.publishBatch(batch);
        } while (published == batch);
    }

    @Scheduled(identity = "execution-commands", every = "${app.messaging.poll-interval:1s}", delayed = "${app.messaging.start-delay:5s}",
        concurrentExecution = ConcurrentExecution.SKIP, skipExecutionIf = MessagingDisabled.class)
    void consumeCommands() {
        consumer.pollOnce();
    }

    @Singleton
    public static class MessagingDisabled implements Scheduled.SkipPredicate {

        private final ExecutionConfig config;

        public MessagingDisabled(ExecutionConfig config) {
            this.config = config;
        }

        @Override
        public boolean test(ScheduledExecution execution) {
            return !config.messaging().enabled();
        }
    }
}
