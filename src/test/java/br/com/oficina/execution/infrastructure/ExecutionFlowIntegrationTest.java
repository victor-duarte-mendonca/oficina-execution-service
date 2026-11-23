package br.com.oficina.execution.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.oficina.execution.application.ports.out.ExecutionChanges;
import br.com.oficina.execution.application.ports.out.ExecutionTaskRepository;
import br.com.oficina.execution.application.ports.out.ExecutionUnitOfWork;
import br.com.oficina.execution.application.ports.out.ExecutionUnitOfWork.ConcurrentTaskUpdateException;
import br.com.oficina.execution.domain.model.ExecutionTask;
import br.com.oficina.execution.domain.model.Phase;
import br.com.oficina.execution.domain.model.TaskStatus;
import br.com.oficina.execution.infrastructure.configuration.ExecutionConfig;
import br.com.oficina.execution.infrastructure.messaging.CommandConsumer;
import br.com.oficina.execution.infrastructure.messaging.OutboxPublisher;
import br.com.oficina.execution.testsupport.LocalStackResource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;

/**
 * O caminho real de uma mensagem, com DynamoDB e SQS do LocalStack: comando na fila do Execution →
 * consumidor → tarefa e resposta gravadas numa transação → relay → resposta na fila do OS. Cobre também
 * a deduplicação pelo inbox e a trava otimista no DynamoDB.
 */
@QuarkusTest
@QuarkusTestResource(LocalStackResource.class)
class ExecutionFlowIntegrationTest {

    private static final AtomicLong WORK_ORDERS = new AtomicLong(1_000);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Inject CommandConsumer consumer;
    @Inject OutboxPublisher publisher;
    @Inject ExecutionTaskRepository repository;
    @Inject ExecutionUnitOfWork unitOfWork;
    @Inject SqsClient sqs;
    @Inject ExecutionConfig config;

    @Test
    void enqueueCommand_shouldCreateTheTaskAndReplyToTheOrderService() throws Exception {
        long workOrderId = WORK_ORDERS.incrementAndGet();
        UUID sagaId = UUID.randomUUID();

        sendCommand(enqueue(UUID.randomUUID(), sagaId, workOrderId));
        assertThat(consumer.pollOnce()).isEqualTo(1);

        assertThat(repository.find(workOrderId, Phase.REPAIR)).get()
            .satisfies(task -> {
                assertThat(task.status()).isEqualTo(TaskStatus.QUEUED);
                assertThat(task.sagaId()).isEqualTo(sagaId);
                assertThat(task.version()).isEqualTo(1L);
            });
        assertThat(publisher.publishBatch(25)).isGreaterThanOrEqualTo(1);

        JsonNode reply = replyFor(workOrderId);
        assertThat(reply.get("type").asText()).isEqualTo("ExecutionEnqueued");
        assertThat(reply.get("sagaId").asText()).isEqualTo(sagaId.toString());
        assertThat(reply.get("correlationId").asText()).isEqualTo("req-it");
        assertThat(reply.at("/payload/position").asInt()).isPositive();
    }

    @Test
    void repeatedCommand_shouldBeAbsorbedByTheInbox() throws Exception {
        long workOrderId = WORK_ORDERS.incrementAndGet();
        String command = enqueue(UUID.randomUUID(), UUID.randomUUID(), workOrderId);

        sendCommand(command);
        consumer.pollOnce();
        sendCommand(command);
        consumer.pollOnce();
        publisher.publishBatch(25);

        assertThat(repliesFor(workOrderId)).hasSize(1);
    }

    @Test
    void staleTask_shouldBeRejectedByTheOptimisticLock() {
        long workOrderId = WORK_ORDERS.incrementAndGet();
        sendCommand(enqueue(UUID.randomUUID(), UUID.randomUUID(), workOrderId));
        consumer.pollOnce();

        ExecutionTask first = repository.find(workOrderId, Phase.REPAIR).orElseThrow();
        ExecutionTask second = repository.find(workOrderId, Phase.REPAIR).orElseThrow();
        first.start("mecanico-a", OffsetDateTime.now());
        second.start("mecanico-b", OffsetDateTime.now());

        unitOfWork.commit(ExecutionChanges.builder().save(first).build());

        assertThatThrownBy(() -> unitOfWork.commit(ExecutionChanges.builder().save(second).build()))
            .isInstanceOf(ConcurrentTaskUpdateException.class);
        assertThat(repository.find(workOrderId, Phase.REPAIR).orElseThrow().assignee()).isEqualTo("mecanico-a");
    }

    @Test
    void malformedCommand_shouldStayInTheQueue() {
        sqs.sendMessage(request -> request.queueUrl(commandsQueue()).messageGroupId("wo-bad")
            .messageDeduplicationId(UUID.randomUUID().toString()).messageBody("{\"type\":"));

        assertThat(consumer.pollOnce()).isZero();
    }

    // ===== Ajudantes =====

    private void sendCommand(String body) {
        sqs.sendMessage(request -> request.queueUrl(commandsQueue()).messageGroupId("wo-test")
            .messageDeduplicationId(UUID.randomUUID().toString()).messageBody(body));
    }

    private JsonNode replyFor(long workOrderId) throws Exception {
        List<JsonNode> replies = repliesFor(workOrderId);
        assertThat(replies).isNotEmpty();
        return replies.get(0);
    }

    /** Lê a fila do OS e devolve as respostas desta OS (as de outros testes são descartadas). */
    private List<JsonNode> repliesFor(long workOrderId) throws Exception {
        List<JsonNode> own = new ArrayList<>();
        for (int attempt = 0; attempt < 3; attempt++) {
            List<Message> messages = sqs.receiveMessage(request -> request.queueUrl(repliesQueue())
                .maxNumberOfMessages(10).waitTimeSeconds(1)).messages();
            for (Message message : messages) {
                JsonNode reply = JSON.readTree(message.body());
                sqs.deleteMessage(request -> request.queueUrl(repliesQueue()).receiptHandle(message.receiptHandle()));
                if (reply.get("workOrderId").asLong() == workOrderId) {
                    own.add(reply);
                }
            }
        }
        return own;
    }

    private String commandsQueue() {
        return config.messaging().queues().commands().orElseThrow();
    }

    private String repliesQueue() {
        return config.messaging().queues().osReplies().orElseThrow();
    }

    private static String enqueue(UUID messageId, UUID sagaId, long workOrderId) {
        return """
            {"messageId":"%s","type":"EnqueueExecution","version":1,"kind":"COMMAND","sagaId":"%s",
             "workOrderId":%d,"orderNumber":"OS-%06d","correlationId":"req-it","occurredAt":"2026-11-23T14:10:01Z",
             "payload":{"vehicle":{"brand":"Fiat","model":"Argo","plate":"ABC1D23"},
                        "items":[{"kind":"SERVICE","name":"Troca de pastilhas","quantity":1}],"priority":0}}
            """.formatted(messageId, sagaId, workOrderId, workOrderId);
    }
}
