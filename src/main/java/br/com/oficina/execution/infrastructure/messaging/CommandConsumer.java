package br.com.oficina.execution.infrastructure.messaging;

import br.com.oficina.execution.application.messaging.InboundMessage;
import br.com.oficina.execution.application.ports.in.HandleExecutionCommandUseCase;
import br.com.oficina.execution.infrastructure.messaging.MessageTransport.ReceivedMessage;
import br.com.oficina.execution.infrastructure.observability.RequestContext;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

/**
 * Processa os comandos do OS. Só confirma (apaga da fila) depois de o caso de uso gravar; malformado ou
 * conflito de versão deixa a mensagem voltar — até o limite da fila, quando ela vai para a DLQ.
 */
@ApplicationScoped
public class CommandConsumer {

    private static final Logger LOG = Logger.getLogger(CommandConsumer.class);

    private final MessageTransport transport;
    private final MessageEnvelopeCodec codec;
    private final HandleExecutionCommandUseCase handler;

    public CommandConsumer(MessageTransport transport, MessageEnvelopeCodec codec, HandleExecutionCommandUseCase handler) {
        this.transport = transport;
        this.codec = codec;
        this.handler = handler;
    }

    /** @return quantos comandos foram confirmados */
    public int pollOnce() {
        int acknowledged = 0;
        for (ReceivedMessage received : transport.receiveCommands()) {
            if (process(received)) {
                acknowledged++;
            }
        }
        return acknowledged;
    }

    boolean process(ReceivedMessage received) {
        try {
            InboundMessage command = codec.decode(received.body());
            RequestContext.putCorrelationId(command.correlationId());
            handler.handle(command);
            transport.acknowledge(received);
            return true;
        } catch (RuntimeException e) {
            LOG.errorf(e, "Falha ao processar comando (entrega %d); ele voltará à fila", received.receiveCount());
            return false;
        } finally {
            RequestContext.clearCorrelationId();
        }
    }
}
