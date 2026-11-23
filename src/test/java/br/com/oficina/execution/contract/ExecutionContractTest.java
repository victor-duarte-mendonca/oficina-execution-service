package br.com.oficina.execution.contract;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.oficina.execution.application.messaging.ExecutionMessageFactory;
import br.com.oficina.execution.application.messaging.InboundMessage;
import br.com.oficina.execution.application.messaging.MessageKind;
import br.com.oficina.execution.application.messaging.OutboundMessage;
import br.com.oficina.execution.application.ports.in.HandleExecutionCommandUseCase.Result;
import br.com.oficina.execution.application.service.ExecutionCommandHandler;
import br.com.oficina.execution.application.service.TaskEvents;
import br.com.oficina.execution.application.service.command.CancelExecutionStrategy;
import br.com.oficina.execution.application.service.command.DiagnosisStartedStrategy;
import br.com.oficina.execution.application.service.command.EnqueueExecutionStrategy;
import br.com.oficina.execution.domain.event.ExecutionEvent;
import br.com.oficina.execution.domain.model.ExecutionTask;
import br.com.oficina.execution.domain.model.Phase;
import br.com.oficina.execution.domain.model.VehicleInfo;
import br.com.oficina.execution.domain.model.WorkItem;
import br.com.oficina.execution.infrastructure.messaging.MessageEnvelopeCodec;
import br.com.oficina.execution.testsupport.InMemoryExecutionStore;
import br.com.oficina.execution.testsupport.RecordingMetrics;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Contrato dos dois lados. Produtor: cada evento que o Execution publica, pelo codec real, é válido em
 * {@code execution-events.schema.json} (o dono do schema é este serviço). Consumidor: os comandos de
 * exemplo do OS são válidos no schema do OS e são tratados por este serviço sem erro.
 */
class ExecutionContractTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-23T14:00:00Z");
    private static final UUID SAGA = UUID.fromString("0b7d2f5e-1111-4000-8000-000000000001");

    private final MessageEnvelopeCodec codec = new MessageEnvelopeCodec(ContractSchemas.MAPPER);
    private final ExecutionMessageFactory factory = new ExecutionMessageFactory();

    static Stream<ExecutionEvent> events() {
        return Stream.of(new ExecutionEvent.Enqueued(), new ExecutionEvent.Started("mecanico"),
            new ExecutionEvent.Finished("pastilhas trocadas"), new ExecutionEvent.Failed("peça indisponível"),
            new ExecutionEvent.Cancelled(true), new ExecutionEvent.Progress(Phase.REPAIR, "pastilhas removidas"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("events")
    void publishedEvent_shouldMatchTheContract(ExecutionEvent event) {
        OutboundMessage message = factory.from(event, repairTask(), () -> 3, UUID.randomUUID(), NOW);

        JsonNode wire = wire(message);

        assertThat(ContractSchemas.validate("execution-events", wire)).isEmpty();
        assertThat(wire.get("type").asText()).isEqualTo(event.type());
    }

    @Test
    void repliesWithoutTask_shouldMatchTheContract() {
        InboundMessage command = InboundMessage.of(UUID.randomUUID(), "EnqueueExecution",
            MessageKind.COMMAND, SAGA, 1L, "OS-000001", Map.of());

        assertThat(ContractSchemas.validate("execution-events",
            wire(factory.replyTo(command, new ExecutionEvent.EnqueueRefused("reparo já concluído"), NOW)))).isEmpty();
        assertThat(ContractSchemas.validate("execution-events",
            wire(factory.replyTo(command, new ExecutionEvent.Cancelled(false), NOW)))).isEmpty();
    }

    @Test
    void everyEventOfTheCatalog_shouldBeProducedByThisService() {
        JsonNode typeEnum = ContractSchemas.read("execution-events.schema.json").at("/allOf/1/properties/type/enum");
        Set<String> catalog = new HashSet<>();
        StreamSupport.stream(typeEnum.spliterator(), false).map(JsonNode::asText).forEach(catalog::add);

        Set<String> produced = new HashSet<>();
        events().map(ExecutionEvent::type).forEach(produced::add);
        produced.add(new ExecutionEvent.EnqueueRefused("x").type());

        assertThat(produced).containsExactlyInAnyOrderElementsOf(catalog);
    }

    @Test
    void consumedCommandExamples_shouldBeValidAndHandled() {
        InMemoryExecutionStore store = new InMemoryExecutionStore();
        TaskEvents taskEvents = new TaskEvents(store, factory);
        ExecutionCommandHandler handler = new ExecutionCommandHandler(List.of(
            new EnqueueExecutionStrategy(store, taskEvents, factory),
            new CancelExecutionStrategy(store, taskEvents, factory),
            new DiagnosisStartedStrategy(store, taskEvents)), store, new RecordingMetrics(), Clock.systemUTC());

        List<JsonNode> examples = StreamSupport.stream(
            ContractSchemas.read("examples/os-commands.json").spliterator(), false).toList();

        assertThat(examples).hasSize(3).allSatisfy(example -> {
            assertThat(ContractSchemas.validate("os-commands", example)).as(example.get("type").asText()).isEmpty();
            assertThat(handler.handle(codec.decode(example.toString()))).isEqualTo(Result.PROCESSED);
        });
        assertThat(store.find(1L, Phase.REPAIR)).isPresent();
        assertThat(store.find(1L, Phase.DIAGNOSIS)).isPresent();
        assertThat(store.outbox()).extracting(OutboundMessage::type)
            .containsExactly("ExecutionEnqueued", "ExecutionCancelled");
    }

    private JsonNode wire(OutboundMessage message) {
        try {
            return ContractSchemas.MAPPER.readTree(codec.encode(message, "req-1",
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ExecutionTask repairTask() {
        return ExecutionTask.queueRepair(1L, "OS-000001", SAGA, new VehicleInfo("Fiat", "Argo", "ABC1D23"),
            List.of(new WorkItem(WorkItem.Kind.SERVICE, "Troca de pastilhas", 1)), 0, NOW);
    }
}
