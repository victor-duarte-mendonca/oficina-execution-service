package br.com.oficina.execution.application.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Comando malformado nunca vira tarefa com dados inventados: {@link InvalidMessageException}, e a mensagem vai à DLQ. */
class MessagePayloadTest {

    @Test
    void typedFields_shouldBeRead() {
        MessagePayload payload = new MessagePayload(Map.of(
            "text", "ok", "number", 7, "flag", true,
            "object", Map.of("plate", "ABC1D23"),
            "list", List.of(Map.of("name", "Pastilha"))));

        assertThat(payload.text("text")).isEqualTo("ok");
        assertThat(payload.integer("number")).isEqualTo(7);
        assertThat(payload.longInteger("number")).isEqualTo(7L);
        assertThat(payload.flag("flag")).isTrue();
        assertThat(payload.object("object").text("plate")).isEqualTo("ABC1D23");
        assertThat(payload.objects("list")).singleElement().satisfies(item -> assertThat(item.text("name")).isEqualTo("Pastilha"));
        assertThat(payload.integerOr("missing", 3)).isEqualTo(3);
        assertThat(payload.integerOr("number", 3)).isEqualTo(7);
        assertThat(payload.optionalText("missing")).isEmpty();
    }

    @Test
    void wrongTypesOrMissingFields_shouldBeRejected() {
        Map<String, Object> values = new HashMap<>();
        values.put("blank", "  ");
        values.put("text", "x");
        values.put("nothing", null);
        values.put("mixed", List.of(Map.of("a", 1), "texto"));
        MessagePayload payload = new MessagePayload(values);

        assertThatThrownBy(() -> payload.text("blank")).isInstanceOf(InvalidMessageException.class);
        assertThatThrownBy(() -> payload.text("nothing")).isInstanceOf(InvalidMessageException.class);
        assertThatThrownBy(() -> payload.integer("text")).isInstanceOf(InvalidMessageException.class);
        assertThatThrownBy(() -> payload.flag("text")).isInstanceOf(InvalidMessageException.class);
        assertThatThrownBy(() -> payload.object("text")).isInstanceOf(InvalidMessageException.class);
        assertThatThrownBy(() -> payload.objects("text")).isInstanceOf(InvalidMessageException.class);
        assertThatThrownBy(() -> payload.objects("mixed")).isInstanceOf(InvalidMessageException.class);
    }

    @Test
    void nullMap_shouldBeAnEmptyPayload() {
        assertThatThrownBy(() -> new MessagePayload(null).text("x")).isInstanceOf(InvalidMessageException.class);
    }
}
