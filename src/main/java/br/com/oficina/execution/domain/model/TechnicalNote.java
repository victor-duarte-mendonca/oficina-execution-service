package br.com.oficina.execution.domain.model;

import br.com.oficina.execution.domain.exception.ExecutionRuleViolation;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Anotação técnica do mecânico durante o diagnóstico ou o reparo. Imutável: nota não se edita, se acrescenta. */
public record TechnicalNote(UUID id, Phase phase, String author, String text, Kind kind, OffsetDateTime at) {

    public static final int MAX_LENGTH = 1000;

    public enum Kind { OBSERVATION, FINDING, PART_REPLACED, TEST_RESULT }

    public TechnicalNote {
        if (text == null || text.isBlank() || text.length() > MAX_LENGTH) {
            throw new ExecutionRuleViolation("Nota técnica deve ter de 1 a " + MAX_LENGTH + " caracteres");
        }
        if (author == null || author.isBlank()) {
            throw new ExecutionRuleViolation("Nota técnica sem autor");
        }
    }
}
