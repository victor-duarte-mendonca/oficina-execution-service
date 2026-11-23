package br.com.oficina.execution.infrastructure.messaging;

import br.com.oficina.execution.infrastructure.persistence.DynamoOutbox;
import br.com.oficina.execution.infrastructure.persistence.DynamoOutbox.PendingMessage;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

/**
 * Publica o outbox na ordem de criação. Na primeira falha o lote para: respostas posteriores da mesma
 * OS não podem passar à frente da que falhou. Uma queda entre o envio e a marcação republica a
 * mensagem, e a deduplicação do SQS FIFO (mesmo {@code messageId}) evita a cópia.
 */
@ApplicationScoped
public class OutboxPublisher {

    private static final Logger LOG = Logger.getLogger(OutboxPublisher.class);

    private final DynamoOutbox outbox;
    private final MessageTransport transport;

    public OutboxPublisher(DynamoOutbox outbox, MessageTransport transport) {
        this.outbox = outbox;
        this.transport = transport;
    }

    /** @return quantas mensagens foram publicadas */
    public int publishBatch(int limit) {
        int published = 0;
        for (PendingMessage message : outbox.pending(limit)) {
            try {
                transport.sendToOrderService(message.groupId(), message.messageId(), message.body());
                outbox.markPublished(message.messageId());
                published++;
            } catch (RuntimeException e) {
                LOG.warnf(e, "Falha ao publicar %s (%s); nova tentativa no próximo ciclo",
                    message.messageId(), message.type());
                break;
            }
        }
        return published;
    }
}
