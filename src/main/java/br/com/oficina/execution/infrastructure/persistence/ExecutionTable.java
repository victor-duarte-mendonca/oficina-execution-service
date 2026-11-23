package br.com.oficina.execution.infrastructure.persistence;

import br.com.oficina.execution.domain.model.Phase;
import br.com.oficina.execution.domain.model.TaskStatus;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

/**
 * Modelo de tabela única do Execution Service (spec 02 §4). Uma tabela, quatro tipos de item:
 *
 * <pre>
 * Tarefa   PK=WO#&lt;id&gt;        SK=TASK#&lt;fase&gt;                 GSI1=QUEUE#&lt;fase&gt;#&lt;status&gt; / prioridade#entrada#os
 * Nota     PK=WO#&lt;id&gt;        SK=NOTE#&lt;fase&gt;#&lt;instante&gt;#&lt;id&gt;
 * Inbox    PK=MSG#&lt;messageId&gt; SK=INBOX                       (TTL de 7 dias)
 * Outbox   PK=OUT#&lt;messageId&gt; SK=OUTBOX                      GSI2=PENDING / criação#id (índice esparso)
 * </pre>
 *
 * Padrões de acesso: tarefas e notas de uma OS (PK), fila ordenada de uma fase (GSI1), outbox pendente
 * em ordem de criação (GSI2). O GSI2 é esparso: publicar remove o atributo e a mensagem sai do índice.
 */
public final class ExecutionTable {

    public static final String PK = "pk";
    public static final String SK = "sk";
    public static final String GSI1 = "gsi1";
    public static final String GSI1_PK = "gsi1pk";
    public static final String GSI1_SK = "gsi1sk";
    public static final String GSI2 = "gsi2";
    public static final String GSI2_PK = "gsi2pk";
    public static final String GSI2_SK = "gsi2sk";
    public static final String TTL = "ttl";
    public static final String PENDING = "PENDING";

    private ExecutionTable() {
    }

    static String workOrderKey(Long workOrderId) {
        return "WO#" + workOrderId;
    }

    static String taskKey(Phase phase) {
        return "TASK#" + phase.name();
    }

    static String notePrefix() {
        return "NOTE#";
    }

    static String noteKey(Phase phase, OffsetDateTime at, UUID id) {
        return notePrefix() + phase.name() + "#" + instant(at) + "#" + id;
    }

    static String queueKey(Phase phase, TaskStatus status) {
        return "QUEUE#" + phase.name() + "#" + status.name();
    }

    /** Ordena por prioridade (menor = mais urgente), depois por antiguidade; a OS desempata. */
    static String queueSortKey(int priority, OffsetDateTime queuedAt, Long workOrderId) {
        return String.format("%04d#%s#%012d", priority, instant(queuedAt), workOrderId);
    }

    static String inboxKey(UUID messageId) {
        return "MSG#" + messageId;
    }

    static String outboxKey(UUID messageId) {
        return "OUT#" + messageId;
    }

    /** ISO-8601 em UTC: ordenação lexicográfica = ordenação temporal. */
    static String instant(OffsetDateTime value) {
        return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC).toInstant().toString();
    }

    static OffsetDateTime parse(String value) {
        return value == null ? null : OffsetDateTime.parse(value);
    }

    /**
     * Cria a tabela com os dois índices (dev, docker compose e testes). Em AWS a tabela é provisionada
     * pelo Terraform ({@code oficina-infra-database}) com a mesma definição.
     */
    public static void createIfMissing(DynamoDbClient dynamo, String table) {
        try {
            dynamo.createTable(request -> request
                .tableName(table)
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(
                    attribute(PK), attribute(SK), attribute(GSI1_PK), attribute(GSI1_SK),
                    attribute(GSI2_PK), attribute(GSI2_SK))
                .keySchema(key(PK, KeyType.HASH), key(SK, KeyType.RANGE))
                .globalSecondaryIndexes(index(GSI1, GSI1_PK, GSI1_SK), index(GSI2, GSI2_PK, GSI2_SK)));
            dynamo.waiter().waitUntilTableExists(request -> request.tableName(table));
            dynamo.updateTimeToLive(request -> request.tableName(table)
                .timeToLiveSpecification(ttl -> ttl.attributeName(TTL).enabled(true)));
        } catch (ResourceInUseException alreadyExists) {
            // Outra réplica (ou execução anterior) já criou.
        }
    }

    private static AttributeDefinition attribute(String name) {
        return AttributeDefinition.builder().attributeName(name).attributeType(ScalarAttributeType.S).build();
    }

    private static KeySchemaElement key(String name, KeyType type) {
        return KeySchemaElement.builder().attributeName(name).keyType(type).build();
    }

    private static GlobalSecondaryIndex index(String name, String partition, String sort) {
        return GlobalSecondaryIndex.builder()
            .indexName(name)
            .keySchema(key(partition, KeyType.HASH), key(sort, KeyType.RANGE))
            .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
            .build();
    }
}
