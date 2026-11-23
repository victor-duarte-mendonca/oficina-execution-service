package br.com.oficina.execution.application.messaging;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Resposta publicada para o OS (fila {@code os-saga-replies}). {@code correlationId} e
 * {@code traceparent} são acrescentados pela infraestrutura ao gravar no outbox.
 */
public record OutboundMessage(UUID messageId,
                              String type,
                              MessageKind kind,
                              UUID sagaId,
                              Long workOrderId,
                              String orderNumber,
                              UUID causationId,
                              OffsetDateTime occurredAt,
                              Map<String, Object> payload) {

    public static final int CONTRACT_VERSION = 1;

    public OutboundMessage {
        Objects.requireNonNull(messageId);
        Objects.requireNonNull(type);
        Objects.requireNonNull(kind);
        Objects.requireNonNull(workOrderId);
        Objects.requireNonNull(occurredAt);
        payload = Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }

    /** Mesmo grupo FIFO do OS: as respostas de uma OS chegam na ordem em que aconteceram. */
    public String groupId() {
        return "wo-" + workOrderId;
    }
}
