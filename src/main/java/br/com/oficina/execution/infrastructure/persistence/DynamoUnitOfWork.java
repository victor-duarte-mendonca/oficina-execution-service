package br.com.oficina.execution.infrastructure.persistence;

import static br.com.oficina.execution.infrastructure.persistence.TaskItemMapper.n;
import static br.com.oficina.execution.infrastructure.persistence.TaskItemMapper.s;

import br.com.oficina.execution.application.messaging.OutboundMessage;
import br.com.oficina.execution.application.ports.out.ExecutionChanges;
import br.com.oficina.execution.application.ports.out.ExecutionUnitOfWork;
import br.com.oficina.execution.domain.model.ExecutionTask;
import br.com.oficina.execution.infrastructure.configuration.ExecutionConfig;
import br.com.oficina.execution.infrastructure.messaging.MessageEnvelopeCodec;
import br.com.oficina.execution.infrastructure.observability.RequestContext;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

/**
 * Grava um {@link ExecutionChanges} numa única {@code TransactWriteItems}: tarefas (com trava otimista
 * pela versão), notas novas, mensagens no outbox e o registro no inbox. É o equivalente, no DynamoDB, à
 * transação local do OS — estado, mensagens e deduplicação nunca divergem.
 */
@ApplicationScoped
public class DynamoUnitOfWork implements ExecutionUnitOfWork {

    private static final String CONDITIONAL_CHECK_FAILED = "ConditionalCheckFailed";
    private static final Duration INBOX_RETENTION = Duration.ofDays(7);

    private final DynamoDbClient dynamo;
    private final String table;
    private final MessageEnvelopeCodec codec;
    private final Clock clock;

    public DynamoUnitOfWork(DynamoDbClient dynamo, ExecutionConfig config, MessageEnvelopeCodec codec, Clock clock) {
        this.dynamo = dynamo;
        this.table = config.dynamodb().table();
        this.codec = codec;
        this.clock = clock;
    }

    @Override
    public void commit(ExecutionChanges changes) {
        if (changes.isEmpty()) {
            return;
        }
        List<TransactWriteItem> writes = new ArrayList<>();
        changes.tasks().forEach(task -> addTask(writes, task));
        changes.messages().forEach(message -> writes.add(put(outboxItem(message), null)));
        int inboxIndex = changes.processedMessage().map(messageId -> {
            writes.add(put(inboxItem(messageId), "attribute_not_exists(" + ExecutionTable.PK + ")"));
            return writes.size() - 1;
        }).orElse(-1);
        try {
            dynamo.transactWriteItems(request -> request.transactItems(writes));
        } catch (TransactionCanceledException cancelled) {
            throw translate(cancelled, inboxIndex, changes);
        }
    }

    private void addTask(List<TransactWriteItem> writes, ExecutionTask task) {
        long newVersion = task.version() == null ? 1 : task.version() + 1;
        Map<String, AttributeValue> item =
            TaskItemMapper.toItem(task, newVersion);
        if (task.version() == null) {
            writes.add(put(item, "attribute_not_exists(" + ExecutionTable.PK + ")"));
        } else {
            writes.add(TransactWriteItem.builder().put(Put.builder()
                .tableName(table)
                .item(item)
                .conditionExpression("#version = :expected")
                .expressionAttributeNames(Map.of("#version", "version"))
                .expressionAttributeValues(Map.of(":expected", n(task.version())))
                .build()).build());
        }
        task.newNotes().forEach(note -> writes.add(put(TaskItemMapper.toItem(task.workOrderId(), note),
            "attribute_not_exists(" + ExecutionTable.PK + ")")));
    }

    private Map<String, AttributeValue> outboxItem(OutboundMessage message) {
        String body = codec.encode(message, RequestContext.correlationId().orElse(null),
            RequestContext.traceparent().orElse(null));
        String createdAt = ExecutionTable.instant(message.occurredAt());
        return Map.of(
            ExecutionTable.PK, s(ExecutionTable.outboxKey(message.messageId())),
            ExecutionTable.SK, s("OUTBOX"),
            ExecutionTable.GSI2_PK, s(ExecutionTable.PENDING),
            ExecutionTable.GSI2_SK, s(createdAt + "#" + message.messageId()),
            "entity", s("OUTBOX"),
            "messageId", s(message.messageId().toString()),
            "groupId", s(message.groupId()),
            "type", s(message.type()),
            "body", s(body),
            "createdAt", s(createdAt));
    }

    private Map<String, AttributeValue> inboxItem(UUID messageId) {
        Instant now = OffsetDateTime.now(clock).toInstant();
        return Map.of(
            ExecutionTable.PK, s(ExecutionTable.inboxKey(messageId)),
            ExecutionTable.SK, s("INBOX"),
            "entity", s("INBOX"),
            "processedAt", s(now.toString()),
            ExecutionTable.TTL, n(now.plus(INBOX_RETENTION).getEpochSecond()));
    }

    private TransactWriteItem put(Map<String, AttributeValue> item,
                                  String condition) {
        Put.Builder put = Put.builder().tableName(table).item(item);
        if (condition != null) {
            put.conditionExpression(condition);
        }
        return TransactWriteItem.builder().put(put.build()).build();
    }

    /** O DynamoDB devolve um motivo por item: o do inbox diz "repetida"; o de uma tarefa diz "concorrência". */
    private static RuntimeException translate(TransactionCanceledException cancelled, int inboxIndex,
                                              ExecutionChanges changes) {
        List<CancellationReason> reasons = cancelled.cancellationReasons();
        if (inboxIndex >= 0 && inboxIndex < reasons.size()
            && CONDITIONAL_CHECK_FAILED.equals(reasons.get(inboxIndex).code())) {
            return new DuplicateMessageException(changes.processedMessage().orElseThrow().toString());
        }
        boolean conflict = reasons.stream().anyMatch(reason -> CONDITIONAL_CHECK_FAILED.equals(reason.code()));
        return conflict ? new ConcurrentTaskUpdateException(cancelled.getMessage()) : cancelled;
    }
}
