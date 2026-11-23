package br.com.oficina.execution.infrastructure.persistence;

import static br.com.oficina.execution.infrastructure.persistence.TaskItemMapper.s;

import br.com.oficina.execution.application.ports.out.ExecutionTaskRepository;
import br.com.oficina.execution.domain.model.ExecutionTask;
import br.com.oficina.execution.domain.model.Phase;
import br.com.oficina.execution.domain.model.TaskStatus;
import br.com.oficina.execution.domain.model.TechnicalNote;
import br.com.oficina.execution.infrastructure.configuration.ExecutionConfig;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.Select;

/** Leituras do Execution no DynamoDB, sempre por chave ou índice (nenhum {@code Scan}). */
@ApplicationScoped
public class DynamoTaskRepository implements ExecutionTaskRepository {

    private final DynamoDbClient dynamo;
    private final String table;

    public DynamoTaskRepository(DynamoDbClient dynamo, ExecutionConfig config) {
        this.dynamo = dynamo;
        this.table = config.dynamodb().table();
    }

    @Override
    public Optional<ExecutionTask> find(Long workOrderId, Phase phase) {
        Map<String, AttributeValue> item = dynamo.getItem(request -> request
                .tableName(table)
                .consistentRead(true)
                .key(Map.of(ExecutionTable.PK, s(ExecutionTable.workOrderKey(workOrderId)),
                    ExecutionTable.SK, s(ExecutionTable.taskKey(phase)))))
            .item();
        return item == null || item.isEmpty() ? Optional.empty() : Optional.of(TaskItemMapper.toTask(item));
    }

    @Override
    public List<ExecutionTask> findByWorkOrder(Long workOrderId) {
        return queryWorkOrder(workOrderId, "TASK#").stream()
            .map(TaskItemMapper::toTask)
            .sorted(Comparator.comparing(ExecutionTask::phase))
            .toList();
    }

    @Override
    public List<TechnicalNote> findNotes(Long workOrderId) {
        return queryWorkOrder(workOrderId, ExecutionTable.notePrefix()).stream()
            .map(TaskItemMapper::toNote)
            .sorted(Comparator.comparing(TechnicalNote::at))
            .toList();
    }

    @Override
    public List<ExecutionTask> findQueue(Phase phase, TaskStatus status, int limit) {
        return dynamo.query(request -> request
                .tableName(table)
                .indexName(ExecutionTable.GSI1)
                .keyConditionExpression("#pk = :queue")
                .expressionAttributeNames(Map.of("#pk", ExecutionTable.GSI1_PK))
                .expressionAttributeValues(Map.of(":queue", s(ExecutionTable.queueKey(phase, status))))
                .scanIndexForward(true)
                .limit(limit))
            .items().stream()
            .map(TaskItemMapper::toTask)
            .toList();
    }

    @Override
    public int positionInQueue(ExecutionTask task) {
        String own = ExecutionTable.queueSortKey(task.priority(), task.queuedAt(), task.workOrderId());
        int ahead = dynamo.query(request -> request
                .tableName(table)
                .indexName(ExecutionTable.GSI1)
                .select(Select.COUNT)
                .keyConditionExpression("#pk = :queue AND #sk < :own")
                .expressionAttributeNames(Map.of("#pk", ExecutionTable.GSI1_PK, "#sk", ExecutionTable.GSI1_SK))
                .expressionAttributeValues(Map.of(
                    ":queue", s(ExecutionTable.queueKey(task.phase(), TaskStatus.QUEUED)),
                    ":own", s(own))))
            .count();
        return ahead + 1;
    }

    private List<Map<String, AttributeValue>> queryWorkOrder(Long workOrderId, String sortPrefix) {
        return dynamo.query(request -> request
                .tableName(table)
                .consistentRead(true)
                .keyConditionExpression("#pk = :wo AND begins_with(#sk, :prefix)")
                .expressionAttributeNames(Map.of("#pk", ExecutionTable.PK, "#sk", ExecutionTable.SK))
                .expressionAttributeValues(Map.of(
                    ":wo", s(ExecutionTable.workOrderKey(workOrderId)),
                    ":prefix", s(sortPrefix))))
            .items();
    }
}
