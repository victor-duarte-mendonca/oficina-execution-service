package br.com.oficina.execution.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.oficina.execution.infrastructure.configuration.ExecutionConfig;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** O relay esvazia o outbox em lotes; os agendamentos param quando a mensageria está desligada. */
class ExecutionJobsTest {

    @Test
    void relay_shouldKeepPublishingWhileBatchesComeFull() {
        Deque<Integer> batches = new ArrayDeque<>(List.of(25, 25, 4));
        int[] calls = {0};
        OutboxPublisher publisher = new OutboxPublisher(null, null) {
            @Override
            public int publishBatch(int limit) {
                calls[0]++;
                return batches.pop();
            }
        };

        new ExecutionJobs(publisher, null, config(true)).relayOutbox();

        assertThat(calls[0]).isEqualTo(3);
    }

    @Test
    void consumer_shouldPollOncePerExecution() {
        int[] polls = {0};
        CommandConsumer consumer = new CommandConsumer(null, null, null) {
            @Override
            public int pollOnce() {
                return ++polls[0];
            }
        };

        new ExecutionJobs(null, consumer, config(true)).consumeCommands();

        assertThat(polls[0]).isEqualTo(1);
    }

    @Test
    void skipPredicate_shouldFollowTheMessagingFlag() {
        assertThat(new ExecutionJobs.MessagingDisabled(config(false)).test(null)).isTrue();
        assertThat(new ExecutionJobs.MessagingDisabled(config(true)).test(null)).isFalse();
    }

    private static ExecutionConfig config(boolean enabled) {
        ExecutionConfig.Messaging messaging = new ExecutionConfig.Messaging() {
            @Override public boolean enabled() { return enabled; }
            @Override public Queues queues() { return null; }
            @Override public int maxMessages() { return 10; }
            @Override public int waitSeconds() { return 1; }
            @Override public int relayBatchSize() { return 25; }
        };
        return new ExecutionConfig() {
            @Override public Aws aws() {
                return new Aws() {
                    @Override public String region() { return "us-east-1"; }
                    @Override public Optional<URI> endpointOverride() { return Optional.empty(); }
                };
            }
            @Override public Dynamodb dynamodb() { return null; }
            @Override public Messaging messaging() { return messaging; }
            @Override public Gateway gateway() { return Optional::empty; }
        };
    }
}
