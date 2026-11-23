package br.com.oficina.execution.application.ports.in;

import br.com.oficina.execution.application.messaging.InboundMessage;

/** Porta de entrada do consumidor: um comando da Saga (ou informativo) enviado pelo OS. */
public interface HandleExecutionCommandUseCase {

    Result handle(InboundMessage message);

    enum Result {
        /** Comando aplicado e resposta enfileirada. */
        PROCESSED,
        /** Comando recusado pelas regras de negócio; a recusa foi respondida à Saga. */
        REFUSED,
        /** Mesma mensagem já tratada antes. */
        DUPLICATE,
        /** Tipo desconhecido: confirmado sem efeito. */
        SKIPPED
    }
}
