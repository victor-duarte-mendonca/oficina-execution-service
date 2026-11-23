package br.com.oficina.execution.domain.model;

import br.com.oficina.execution.domain.exception.ExecutionRuleViolation;

/** Veículo como o OS o descreveu no comando (snapshot; o Execution não consulta o cadastro do OS). */
public record VehicleInfo(String brand, String model, String plate) {

    public VehicleInfo {
        if (isBlank(brand) || isBlank(model) || isBlank(plate)) {
            throw new ExecutionRuleViolation("Veículo incompleto: marca, modelo e placa são obrigatórios");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
