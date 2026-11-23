package br.com.oficina.execution.application.service.command;

import br.com.oficina.execution.application.messaging.InvalidMessageException;
import br.com.oficina.execution.application.messaging.MessagePayload;
import br.com.oficina.execution.domain.model.VehicleInfo;
import br.com.oficina.execution.domain.model.WorkItem;
import java.util.List;

/** Leitura dos trechos do contrato comuns a vários comandos (veículo e itens). */
final class CommandPayloads {

    private CommandPayloads() {
    }

    static VehicleInfo vehicle(MessagePayload payload) {
        MessagePayload vehicle = payload.object("vehicle");
        return new VehicleInfo(vehicle.text("brand"), vehicle.text("model"), vehicle.text("plate"));
    }

    static List<WorkItem> items(MessagePayload payload) {
        return payload.objects("items").stream()
            .map(item -> new WorkItem(kind(item.text("kind")), item.text("name"), item.integer("quantity")))
            .toList();
    }

    private static WorkItem.Kind kind(String value) {
        try {
            return WorkItem.Kind.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new InvalidMessageException("Tipo de item desconhecido: " + value);
        }
    }
}
