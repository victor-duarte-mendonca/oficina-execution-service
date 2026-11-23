package br.com.oficina.execution.application.ports.out;

import br.com.oficina.execution.application.messaging.OutboundMessage;
import br.com.oficina.execution.domain.model.ExecutionTask;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Tudo o que um caso de uso grava de uma vez (Unit of Work): tarefas alteradas (com as notas novas),
 * mensagens para o outbox e, quando a origem é uma mensagem, o seu registro no inbox. No DynamoDB
 * isso vira uma única transação — estado, outbox e deduplicação ficam consistentes entre si.
 */
public record ExecutionChanges(List<ExecutionTask> tasks, List<OutboundMessage> messages, Optional<UUID> processedMessage) {

    public ExecutionChanges {
        tasks = List.copyOf(tasks);
        messages = List.copyOf(messages);
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean isEmpty() {
        return tasks.isEmpty() && messages.isEmpty() && processedMessage.isEmpty();
    }

    public static final class Builder {

        private final List<ExecutionTask> tasks = new ArrayList<>();
        private final List<OutboundMessage> messages = new ArrayList<>();
        private UUID processedMessage;

        private Builder() {
        }

        public Builder save(ExecutionTask task) {
            tasks.add(task);
            return this;
        }

        public Builder publish(OutboundMessage message) {
            messages.add(message);
            return this;
        }

        public Builder publishAll(List<OutboundMessage> outbound) {
            messages.addAll(outbound);
            return this;
        }

        public Builder processed(UUID messageId) {
            processedMessage = messageId;
            return this;
        }

        public ExecutionChanges build() {
            return new ExecutionChanges(tasks, messages, Optional.ofNullable(processedMessage));
        }
    }
}
