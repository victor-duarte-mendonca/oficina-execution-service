package br.com.oficina.execution.infrastructure.persistence;

import static br.com.oficina.execution.infrastructure.persistence.TaskItemMapper.n;
import static br.com.oficina.execution.infrastructure.persistence.TaskItemMapper.s;

import br.com.oficina.execution.infrastructure.configuration.ExecutionConfig;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

/**
 * Lado do relay no outbox: lista o que falta publicar (índice esparso GSI2, em ordem de criação) e marca
 * como publicado — o que remove a mensagem do índice e agenda a expiração do item.
 */
@ApplicationScoped
public class DynamoOutbox {

    private static final Duration SENT_RETENTION = Duration.ofDays(3);

    private final DynamoDbClient dynamo;
    private final String table;
    private final Clock clock;

    public DynamoOutbox(DynamoDbClient dynamo, ExecutionConfig config, Clock clock) {
        this.dynamo = dynamo;
        this.table = config.dynamodb().table();
        this.clock = clock;
    }

    public record PendingMessage(UUID messageId, String groupId, String type, String body) {
    }

    public List<PendingMessage> pending(int limit) {
        return dynamo.query(request -> request
                .tableName(table)
                .indexName(ExecutionTable.GSI2)
                .keyConditionExpression("#pk = :pending")
                .expressionAttributeNames(Map.of("#pk", ExecutionTable.GSI2_PK))
                .expressionAttributeValues(Map.of(":pending", s(ExecutionTable.PENDING)))
                .scanIndexForward(true)
                .limit(limit))
            .items().stream()
            .map(item -> new PendingMessage(UUID.fromString(item.get("messageId").s()), item.get("groupId").s(),
                item.get("type").s(), item.get("body").s()))
            .toList();
    }

    /** Idempotente: outra réplica já ter marcado não é erro (a deduplicação do SQS cobre o envio dobrado). */
    public void markPublished(UUID messageId) {
        Instant now = Instant.now(clock);
        try {
            dynamo.updateItem(request -> request
                .tableName(table)
                .key(Map.of(ExecutionTable.PK, s(ExecutionTable.outboxKey(messageId)), ExecutionTable.SK, s("OUTBOX")))
                .updateExpression("REMOVE #gpk, #gsk SET sentAt = :now, #ttl = :ttl")
                .conditionExpression("attribute_exists(#gpk)")
                .expressionAttributeNames(Map.of("#gpk", ExecutionTable.GSI2_PK, "#gsk", ExecutionTable.GSI2_SK,
                    "#ttl", ExecutionTable.TTL))
                .expressionAttributeValues(Map.of(":now", s(now.toString()),
                    ":ttl", n(now.plus(SENT_RETENTION).getEpochSecond()))));
        } catch (ConditionalCheckFailedException alreadyMarked) {
            // Publicada por outra réplica.
        }
    }
}
