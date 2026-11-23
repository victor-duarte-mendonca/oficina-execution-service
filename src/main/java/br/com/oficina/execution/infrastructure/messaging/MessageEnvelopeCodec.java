package br.com.oficina.execution.infrastructure.messaging;

import br.com.oficina.execution.application.messaging.InboundMessage;
import br.com.oficina.execution.application.messaging.InvalidMessageException;
import br.com.oficina.execution.application.messaging.MessageKind;
import br.com.oficina.execution.application.messaging.MessagePayload;
import br.com.oficina.execution.application.messaging.OutboundMessage;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Envelope JSON do contrato ({@code contracts/envelope.schema.json}), igual nos três serviços. Único
 * ponto que conhece o formato de fio.
 */
@ApplicationScoped
public class MessageEnvelopeCodec {

    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() { };

    private final ObjectMapper mapper;
    private final ObjectMapper writer;

    public MessageEnvelopeCodec(ObjectMapper mapper) {
        this.mapper = mapper;
        this.writer = mapper.copy().setSerializationInclusion(JsonInclude.Include.ALWAYS);
    }

    public String encode(OutboundMessage message, String correlationId, String traceparent) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("messageId", message.messageId().toString());
        envelope.put("type", message.type());
        envelope.put("version", OutboundMessage.CONTRACT_VERSION);
        envelope.put("kind", message.kind().name());
        envelope.put("sagaId", message.sagaId() == null ? null : message.sagaId().toString());
        envelope.put("workOrderId", message.workOrderId());
        envelope.put("orderNumber", message.orderNumber());
        envelope.put("correlationId", correlationId);
        envelope.put("causationId", message.causationId() == null ? null : message.causationId().toString());
        envelope.put("occurredAt", message.occurredAt().toInstant().toString());
        envelope.put("traceparent", traceparent);
        envelope.put("payload", message.payload());
        try {
            return writer.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Falha ao serializar a mensagem " + message.messageId(), e);
        }
    }

    public InboundMessage decode(String body) {
        Map<String, Object> envelope = parse(body);
        MessagePayload fields = new MessagePayload(envelope);
        return new InboundMessage(
            uuid(fields.text("messageId"), "messageId"),
            fields.text("type"),
            kind(fields.text("kind")),
            fields.optionalText("sagaId").map(value -> uuid(value, "sagaId")).orElse(null),
            fields.longInteger("workOrderId"),
            fields.optionalText("orderNumber").orElse(null),
            fields.optionalText("correlationId").orElse(null),
            fields.object("payload"));
    }

    private Map<String, Object> parse(String body) {
        try {
            return mapper.readValue(body, JSON_OBJECT);
        } catch (JsonProcessingException e) {
            throw new InvalidMessageException("Mensagem não é um JSON válido: " + e.getOriginalMessage());
        }
    }

    private static UUID uuid(String value, String field) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new InvalidMessageException("Campo '" + field + "' não é um UUID");
        }
    }

    private static MessageKind kind(String value) {
        try {
            return MessageKind.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new InvalidMessageException("Campo 'kind' inválido: " + value);
        }
    }
}
