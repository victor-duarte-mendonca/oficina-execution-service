package br.com.oficina.execution.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.oficina.execution.application.messaging.ExecutionMessageFactory;
import br.com.oficina.execution.application.messaging.InboundMessage;
import br.com.oficina.execution.application.messaging.InvalidMessageException;
import br.com.oficina.execution.application.messaging.MessageKind;
import br.com.oficina.execution.application.messaging.OutboundMessage;
import br.com.oficina.execution.application.ports.in.HandleExecutionCommandUseCase.Result;
import br.com.oficina.execution.application.service.command.CancelExecutionStrategy;
import br.com.oficina.execution.application.service.command.DiagnosisStartedStrategy;
import br.com.oficina.execution.application.service.command.EnqueueExecutionStrategy;
import br.com.oficina.execution.application.service.command.SagaCommandStrategy;
import br.com.oficina.execution.domain.exception.ExecutionRuleViolation;
import br.com.oficina.execution.domain.exception.TaskNotFoundException;
import br.com.oficina.execution.domain.model.Phase;
import br.com.oficina.execution.domain.model.TaskStatus;
import br.com.oficina.execution.domain.model.TechnicalNote;
import br.com.oficina.execution.testsupport.InMemoryExecutionStore;
import br.com.oficina.execution.testsupport.RecordingMetrics;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Casos de uso do Execution sobre armazenamento em memória: as respostas à Saga para cada comando,
 * a idempotência pelo inbox e o trabalho do mecânico pela API.
 */
class ExecutionApplicationTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-23T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID SAGA = UUID.randomUUID();

    private InMemoryExecutionStore store;
    private RecordingMetrics metrics;
    private ExecutionCommandHandler handler;
    private ExecutionWorkService work;

    @BeforeEach
    void setUp() {
        store = new InMemoryExecutionStore();
        metrics = new RecordingMetrics();
        ExecutionMessageFactory messages = new ExecutionMessageFactory();
        TaskEvents taskEvents = new TaskEvents(store, messages);
        List<SagaCommandStrategy> strategies = List.of(
            new EnqueueExecutionStrategy(store, taskEvents, messages),
            new CancelExecutionStrategy(store, taskEvents, messages),
            new DiagnosisStartedStrategy(store, taskEvents));
        handler = new ExecutionCommandHandler(strategies, store, metrics, CLOCK);
        work = new ExecutionWorkService(store, store, taskEvents, metrics, CLOCK);
    }

    @Test
    void metrics_shouldCountCommandsAndPublishedEvents() {
        enqueue(42L);
        work.start(42L, Phase.REPAIR, "mecanico");
        work.fail(42L, "peça indisponível");

        assertThat(metrics.handled()).containsExactly("EnqueueExecution:processed");
        assertThat(metrics.published()).containsExactly("ExecutionEnqueued", "ExecutionStarted", "ExecutionFailed");
    }

    @Nested
    class SagaCommands {

        @Test
        void enqueue_shouldCreateTheRepairAndAnswerWithThePosition() {
            enqueue(41L);

            Result result = enqueue(42L);

            assertThat(result).isEqualTo(Result.PROCESSED);
            OutboundMessage reply = store.lastMessage();
            assertThat(reply.type()).isEqualTo("ExecutionEnqueued");
            assertThat(reply.kind()).isEqualTo(MessageKind.EVENT);
            assertThat(reply.sagaId()).isEqualTo(SAGA);
            assertThat(reply.payload()).containsEntry("position", 2);
            assertThat(store.find(42L, Phase.REPAIR)).get().extracting(t -> t.status()).isEqualTo(TaskStatus.QUEUED);
        }

        @Test
        void sameMessageTwice_shouldBeProcessedOnce() {
            InboundMessage command = enqueueCommand(42L, UUID.randomUUID());

            assertThat(handler.handle(command)).isEqualTo(Result.PROCESSED);
            assertThat(handler.handle(command)).isEqualTo(Result.DUPLICATE);

            assertThat(store.outbox()).hasSize(1);
        }

        @Test
        void enqueueAfterTheRepairWasFinished_shouldBeRefusedToTheSaga() {
            enqueue(42L);
            work.start(42L, Phase.REPAIR, "mecanico");
            work.finish(42L, Phase.REPAIR, "ok");

            Result result = handler.handle(enqueueCommand(42L, UUID.randomUUID()));

            assertThat(result).isEqualTo(Result.REFUSED);
            assertThat(store.lastMessage().type()).isEqualTo("ExecutionEnqueueFailed");
            assertThat(store.lastMessage().payload().get("reason").toString()).contains("concluído");
        }

        @Test
        void cancel_shouldCancelTheQueuedRepair() {
            enqueue(42L);

            handler.handle(command("CancelExecution", 42L, Map.of("reason", "cliente desistiu")));

            assertThat(store.find(42L, Phase.REPAIR).orElseThrow().status()).isEqualTo(TaskStatus.CANCELLED);
            assertThat(store.lastMessage().type()).isEqualTo("ExecutionCancelled");
            assertThat(store.lastMessage().payload()).containsEntry("alreadyFinished", false);
        }

        @Test
        void cancelWithoutRepair_shouldStillConfirm() {
            handler.handle(command("CancelExecution", 99L, Map.of("reason", "nada a cancelar")));

            assertThat(store.lastMessage().type()).isEqualTo("ExecutionCancelled");
            assertThat(store.lastMessage().sagaId()).isEqualTo(SAGA);
        }

        @Test
        void diagnosisStarted_shouldOpenTheDiagnosisWithoutAnsweringTheSaga() {
            InboundMessage info = InboundMessage.of(UUID.randomUUID(), "DiagnosisStarted", MessageKind.INFO, null, 7L,
                "OS-000007", Map.of("vehicle", vehicle(), "notes", "barulho"));

            assertThat(handler.handle(info)).isEqualTo(Result.PROCESSED);
            assertThat(handler.handle(InboundMessage.of(UUID.randomUUID(), "DiagnosisStarted", MessageKind.INFO, null,
                7L, "OS-000007", Map.of("vehicle", vehicle())))).isEqualTo(Result.PROCESSED);

            assertThat(store.find(7L, Phase.DIAGNOSIS)).isPresent();
            assertThat(store.outbox()).isEmpty();
        }

        @Test
        void unknownType_shouldBeSkippedButRecorded() {
            InboundMessage future = command("PrioritizeExecution", 42L, Map.of());

            assertThat(handler.handle(future)).isEqualTo(Result.SKIPPED);
            assertThat(handler.handle(future)).isEqualTo(Result.DUPLICATE);
        }

        @Test
        void malformedCommand_shouldNotBeSwallowed() {
            InboundMessage withoutItems = command("EnqueueExecution", 42L, Map.of("vehicle", vehicle(), "priority", 0));
            InboundMessage withoutSaga = InboundMessage.of(UUID.randomUUID(), "CancelExecution", MessageKind.COMMAND,
                null, 42L, "OS-000042", Map.of("reason", "x"));

            assertThatThrownBy(() -> handler.handle(withoutItems)).isInstanceOf(InvalidMessageException.class);
            assertThatThrownBy(() -> handler.handle(withoutSaga)).isInstanceOf(InvalidMessageException.class);
        }
    }

    @Nested
    class MechanicWork {

        @Test
        void startAndFinish_shouldAnswerTheSaga() {
            enqueue(42L);

            work.start(42L, Phase.REPAIR, "mecanico");
            assertThat(store.lastMessage().type()).isEqualTo("ExecutionStarted");
            assertThat(store.lastMessage().payload()).containsEntry("assignee", "mecanico");

            work.finish(42L, Phase.REPAIR, "pastilhas trocadas");
            assertThat(store.lastMessage().type()).isEqualTo("ExecutionFinished");
            assertThat(store.lastMessage().causationId()).isNull();
        }

        @Test
        void failure_shouldAnswerTheSagaWithTheReason() {
            enqueue(42L);
            work.start(42L, Phase.REPAIR, "mecanico");

            work.fail(42L, "peça indisponível");

            assertThat(store.lastMessage().type()).isEqualTo("ExecutionFailed");
            assertThat(store.lastMessage().payload()).containsEntry("reason", "peça indisponível");
        }

        @Test
        void note_shouldBeStoredAndSentAsInformation() {
            enqueue(42L);
            work.start(42L, Phase.REPAIR, "mecanico");

            work.addNote(42L, Phase.REPAIR, "mecanico", "pastilhas removidas", TechnicalNote.Kind.PART_REPLACED);

            assertThat(store.lastMessage().type()).isEqualTo("ExecutionProgress");
            assertThat(store.lastMessage().kind()).isEqualTo(MessageKind.INFO);
            assertThat(work.details(42L).notes()).singleElement()
                .satisfies(note -> assertThat(note.kind()).isEqualTo("PART_REPLACED"));
        }

        @Test
        void queue_shouldBeOrderedByPriorityThenAge() {
            handler.handle(enqueueCommand(1L, UUID.randomUUID(), 5));
            handler.handle(enqueueCommand(2L, UUID.randomUUID(), 0));

            assertThat(work.queue(Phase.REPAIR, TaskStatus.QUEUED, 500)).extracting(t -> t.workOrderId())
                .containsExactly(2L, 1L);
        }

        @Test
        void unknownWorkOrder_shouldBeNotFound() {
            assertThatThrownBy(() -> work.start(404L, Phase.REPAIR, "mecanico")).isInstanceOf(TaskNotFoundException.class);
            assertThatThrownBy(() -> work.details(404L)).isInstanceOf(TaskNotFoundException.class);
        }

        @Test
        void invalidTransition_shouldNotWriteAnything() {
            enqueue(42L);
            int before = store.outbox().size();

            assertThatThrownBy(() -> work.finish(42L, Phase.REPAIR, "ok")).isInstanceOf(ExecutionRuleViolation.class);

            assertThat(store.outbox()).hasSize(before);
        }
    }

    // ===== Ajudantes =====

    private Result enqueue(Long workOrderId) {
        return handler.handle(enqueueCommand(workOrderId, UUID.randomUUID()));
    }

    private static InboundMessage enqueueCommand(Long workOrderId, UUID messageId) {
        return enqueueCommand(workOrderId, messageId, 0);
    }

    private static InboundMessage enqueueCommand(Long workOrderId, UUID messageId, int priority) {
        return InboundMessage.of(messageId, "EnqueueExecution", MessageKind.COMMAND, SAGA, workOrderId,
            "OS-%06d".formatted(workOrderId), Map.of("vehicle", vehicle(),
                "items", List.of(Map.of("kind", "SERVICE", "name", "Troca de pastilhas", "quantity", 1)),
                "priority", priority));
    }

    private static InboundMessage command(String type, Long workOrderId, Map<String, Object> payload) {
        return InboundMessage.of(UUID.randomUUID(), type, MessageKind.COMMAND, SAGA, workOrderId,
            "OS-%06d".formatted(workOrderId), payload);
    }

    private static Map<String, Object> vehicle() {
        return Map.of("brand", "Fiat", "model", "Argo", "plate", "ABC1D23");
    }
}
