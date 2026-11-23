package br.com.oficina.execution.infrastructure.persistence;

import static br.com.oficina.execution.infrastructure.persistence.ExecutionTable.instant;
import static br.com.oficina.execution.infrastructure.persistence.ExecutionTable.parse;

import br.com.oficina.execution.domain.model.ExecutionTask;
import br.com.oficina.execution.domain.model.Phase;
import br.com.oficina.execution.domain.model.TaskStatus;
import br.com.oficina.execution.domain.model.TechnicalNote;
import br.com.oficina.execution.domain.model.VehicleInfo;
import br.com.oficina.execution.domain.model.WorkItem;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/** Tradução entre o agregado e os itens do DynamoDB. Isola o formato de armazenamento do domínio. */
final class TaskItemMapper {

    private TaskItemMapper() {
    }

    static Map<String, AttributeValue> toItem(ExecutionTask task, long newVersion) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(ExecutionTable.PK, s(ExecutionTable.workOrderKey(task.workOrderId())));
        item.put(ExecutionTable.SK, s(ExecutionTable.taskKey(task.phase())));
        item.put(ExecutionTable.GSI1_PK, s(ExecutionTable.queueKey(task.phase(), task.status())));
        item.put(ExecutionTable.GSI1_SK, s(ExecutionTable.queueSortKey(task.priority(), task.queuedAt(), task.workOrderId())));
        item.put("entity", s("TASK"));
        item.put("workOrderId", n(task.workOrderId()));
        item.put("phase", s(task.phase().name()));
        item.put("status", s(task.status().name()));
        item.put("priority", n(task.priority()));
        item.put("vehicle", AttributeValue.fromM(Map.of(
            "brand", s(task.vehicle().brand()), "model", s(task.vehicle().model()), "plate", s(task.vehicle().plate()))));
        item.put("items", AttributeValue.fromL(task.items().stream()
            .map(workItem -> AttributeValue.fromM(Map.of(
                "kind", s(workItem.kind().name()), "name", s(workItem.name()), "quantity", n(workItem.quantity()))))
            .toList()));
        item.put("queuedAt", s(instant(task.queuedAt())));
        item.put("version", n(newVersion));
        putIfPresent(item, "orderNumber", task.orderNumber());
        putIfPresent(item, "sagaId", task.sagaId() == null ? null : task.sagaId().toString());
        putIfPresent(item, "assignee", task.assignee());
        putIfPresent(item, "startedAt", instant(task.startedAt()));
        putIfPresent(item, "finishedAt", instant(task.finishedAt()));
        putIfPresent(item, "outcome", task.outcome());
        return item;
    }

    static ExecutionTask toTask(Map<String, AttributeValue> item) {
        Map<String, AttributeValue> vehicle = item.get("vehicle").m();
        List<WorkItem> items = item.get("items").l().stream()
            .map(AttributeValue::m)
            .map(m -> new WorkItem(WorkItem.Kind.valueOf(m.get("kind").s()), m.get("name").s(),
                Integer.parseInt(m.get("quantity").n())))
            .toList();
        return ExecutionTask.rehydrate(
            Long.parseLong(item.get("workOrderId").n()),
            Phase.valueOf(item.get("phase").s()),
            text(item, "orderNumber"),
            TaskStatus.valueOf(item.get("status").s()),
            text(item, "sagaId") == null ? null : UUID.fromString(text(item, "sagaId")),
            Integer.parseInt(item.get("priority").n()),
            new VehicleInfo(vehicle.get("brand").s(), vehicle.get("model").s(), vehicle.get("plate").s()),
            items,
            text(item, "assignee"),
            parse(text(item, "queuedAt")),
            parse(text(item, "startedAt")),
            parse(text(item, "finishedAt")),
            text(item, "outcome"),
            Long.parseLong(item.get("version").n()));
    }

    static Map<String, AttributeValue> toItem(Long workOrderId, TechnicalNote note) {
        return Map.of(
            ExecutionTable.PK, s(ExecutionTable.workOrderKey(workOrderId)),
            ExecutionTable.SK, s(ExecutionTable.noteKey(note.phase(), note.at(), note.id())),
            "entity", s("NOTE"),
            "phase", s(note.phase().name()),
            "author", s(note.author()),
            "kind", s(note.kind().name()),
            "text", s(note.text()),
            "at", s(instant(note.at())));
    }

    static TechnicalNote toNote(Map<String, AttributeValue> item) {
        String sortKey = item.get(ExecutionTable.SK).s();
        UUID id = UUID.fromString(sortKey.substring(sortKey.lastIndexOf('#') + 1));
        return new TechnicalNote(id, Phase.valueOf(item.get("phase").s()), item.get("author").s(),
            item.get("text").s(), TechnicalNote.Kind.valueOf(item.get("kind").s()), parse(item.get("at").s()));
    }

    static boolean isTask(Map<String, AttributeValue> item) {
        return item.get(ExecutionTable.SK).s().startsWith("TASK#");
    }

    static AttributeValue s(String value) {
        return AttributeValue.fromS(value);
    }

    static AttributeValue n(Number value) {
        return AttributeValue.fromN(value.toString());
    }

    private static void putIfPresent(Map<String, AttributeValue> item, String name, String value) {
        if (value != null) {
            item.put(name, s(value));
        }
    }

    private static String text(Map<String, AttributeValue> item, String name) {
        AttributeValue value = item.get(name);
        return value == null ? null : value.s();
    }
}
