package br.com.oficina.execution.application.service.command;

import br.com.oficina.execution.application.messaging.InboundMessage;
import br.com.oficina.execution.application.ports.in.HandleExecutionCommandUseCase.Result;
import br.com.oficina.execution.application.ports.out.ExecutionChanges;
import java.time.OffsetDateTime;

/**
 * Tratamento de um tipo de comando vindo do OS (Strategy). Registra no lote o que mudou e a resposta;
 * quem grava é o despachante, junto com o registro da mensagem no inbox.
 */
public interface SagaCommandStrategy {

    /** Tipo da mensagem no contrato ({@code contracts/os-commands.schema.json}). */
    String type();

    Result apply(InboundMessage command, ExecutionChanges.Builder changes, OffsetDateTime now);
}
