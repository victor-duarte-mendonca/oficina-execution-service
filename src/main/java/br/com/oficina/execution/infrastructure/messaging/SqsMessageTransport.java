package br.com.oficina.execution.infrastructure.messaging;

import br.com.oficina.execution.infrastructure.configuration.ExecutionConfig;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;

/** SQS FIFO (ADR 0020): grupo = OS, deduplicação = {@code messageId}. */
@ApplicationScoped
public class SqsMessageTransport implements MessageTransport {

    private final SqsClient sqs;
    private final ExecutionConfig.Messaging config;

    public SqsMessageTransport(SqsClient sqs, ExecutionConfig config) {
        this.sqs = sqs;
        this.config = config.messaging();
    }

    @Override
    public void sendToOrderService(String groupId, UUID deduplicationId, String body) {
        sqs.sendMessage(request -> request
            .queueUrl(required(config.queues().osReplies(), "app.messaging.queues.os-replies"))
            .messageGroupId(groupId)
            .messageDeduplicationId(deduplicationId.toString())
            .messageBody(body));
    }

    @Override
    public List<ReceivedMessage> receiveCommands() {
        return sqs.receiveMessage(request -> request
                .queueUrl(commandsQueue())
                .maxNumberOfMessages(config.maxMessages())
                .waitTimeSeconds(config.waitSeconds())
                .messageSystemAttributeNames(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT))
            .messages().stream()
            .map(message -> new ReceivedMessage(message.body(), message.receiptHandle(),
                Integer.parseInt(message.attributes().getOrDefault(
                    MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT, "1"))))
            .toList();
    }

    @Override
    public void acknowledge(ReceivedMessage message) {
        sqs.deleteMessage(request -> request.queueUrl(commandsQueue()).receiptHandle(message.receipt()));
    }

    private String commandsQueue() {
        return required(config.queues().commands(), "app.messaging.queues.commands");
    }

    private static String required(Optional<String> url, String property) {
        return url.filter(value -> !value.isBlank())
            .orElseThrow(() -> new IllegalStateException("Fila não configurada: " + property));
    }
}
