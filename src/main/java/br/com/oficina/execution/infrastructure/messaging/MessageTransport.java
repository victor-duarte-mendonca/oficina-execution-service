package br.com.oficina.execution.infrastructure.messaging;

import java.util.List;
import java.util.UUID;

/** Transporte físico (SQS FIFO). Separado do relay e do consumidor para testá-los sem broker. */
public interface MessageTransport {

    /** Publica na fila de respostas do OS. */
    void sendToOrderService(String groupId, UUID deduplicationId, String body);

    /** Próximos comandos destinados ao Execution (long polling). */
    List<ReceivedMessage> receiveCommands();

    /** Apaga o comando da fila; sem isso ele volta depois do visibility timeout. */
    void acknowledge(ReceivedMessage message);

    record ReceivedMessage(String body, String receipt, int receiveCount) {
    }
}
