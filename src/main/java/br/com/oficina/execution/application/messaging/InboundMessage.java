package br.com.oficina.execution.application.messaging;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Comando (ou informativo) recebido do OS, já decodificado do envelope (spec 03 §2). */
public record InboundMessage(UUID messageId,
                             String type,
                             MessageKind kind,
                             UUID sagaId,
                             Long workOrderId,
                             String orderNumber,
                             String correlationId,
                             MessagePayload payload) {

    public InboundMessage {
        Objects.requireNonNull(messageId);
        Objects.requireNonNull(type);
        Objects.requireNonNull(kind);
        Objects.requireNonNull(workOrderId);
        Objects.requireNonNull(payload);
    }

    public static InboundMessage of(UUID messageId, String type, MessageKind kind, UUID sagaId, Long workOrderId,
                                    String orderNumber, Map<String, Object> payload) {
        return new InboundMessage(messageId, type, kind, sagaId, workOrderId, orderNumber, null,
            new MessagePayload(payload));
    }

    public UUID requiredSagaId() {
        if (sagaId == null) {
            throw new InvalidMessageException("Comando " + type + " sem sagaId");
        }
        return sagaId;
    }
}
