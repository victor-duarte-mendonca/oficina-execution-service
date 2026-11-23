package br.com.oficina.execution.application.messaging;

import br.com.oficina.execution.domain.event.ExecutionEvent;
import br.com.oficina.execution.domain.model.ExecutionTask;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntSupplier;

/**
 * Converte os eventos do agregado nas mensagens do contrato ({@code contracts/execution-events.schema.json}).
 * Eventos de reparo vão como {@code EVENT} com o {@code sagaId} da tarefa; anotações vão como
 * {@code INFO} (não movem a Saga).
 */
@ApplicationScoped
public class ExecutionMessageFactory {

    /**
     * @param position posição na fila, consultada só se houver {@code ExecutionEnqueued}
     * @param causationId comando que provocou o evento; {@code null} quando a origem foi a API
     */
    public OutboundMessage from(ExecutionEvent event, ExecutionTask task, IntSupplier position, UUID causationId,
                                OffsetDateTime now) {
        Map<String, Object> payload = switch (event) {
            case ExecutionEvent.Enqueued ignored -> fields("position", position.getAsInt());
            case ExecutionEvent.EnqueueRefused refused -> fields("reason", refused.reason());
            case ExecutionEvent.Started started -> fields("assignee", started.assignee());
            case ExecutionEvent.Finished finished -> fields("summary", finished.summary());
            case ExecutionEvent.Failed failed -> fields("reason", failed.reason());
            case ExecutionEvent.Cancelled cancelled -> fields("alreadyFinished", cancelled.alreadyFinished());
            case ExecutionEvent.Progress progress -> fields("phase", progress.phase().name(), "note", progress.note());
        };
        MessageKind kind = event instanceof ExecutionEvent.Progress ? MessageKind.INFO : MessageKind.EVENT;
        return new OutboundMessage(UUID.randomUUID(), event.type(), kind, task.sagaId(), task.workOrderId(),
            task.orderNumber(), causationId, now, payload);
    }

    /**
     * Resposta a um comando que não alterou nenhuma tarefa (recusa de enfileiramento, cancelamento de OS sem
     * reparo): vai para a Saga de origem do comando.
     */
    public OutboundMessage replyTo(InboundMessage command, ExecutionEvent event, OffsetDateTime now) {
        Map<String, Object> payload = switch (event) {
            case ExecutionEvent.EnqueueRefused refused -> fields("reason", refused.reason());
            case ExecutionEvent.Cancelled cancelled -> fields("alreadyFinished", cancelled.alreadyFinished());
            default -> throw new IllegalArgumentException(event.type() + " exige uma tarefa");
        };
        return new OutboundMessage(UUID.randomUUID(), event.type(), MessageKind.EVENT, command.requiredSagaId(),
            command.workOrderId(), command.orderNumber(), command.messageId(), now, payload);
    }

    private static Map<String, Object> fields(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }
}
