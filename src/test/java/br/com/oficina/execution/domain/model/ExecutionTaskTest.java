package br.com.oficina.execution.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.oficina.execution.domain.event.ExecutionEvent;
import br.com.oficina.execution.domain.exception.ExecutionRuleViolation;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Invariantes e eventos da tarefa de execução (spec 06 §2). */
class ExecutionTaskTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-23T12:00:00Z");
    private static final VehicleInfo VEHICLE = new VehicleInfo("Fiat", "Argo", "ABC1D23");
    private static final List<WorkItem> ITEMS = List.of(new WorkItem(WorkItem.Kind.SERVICE, "Troca de pastilhas", 1));
    private static final UUID SAGA = UUID.randomUUID();

    private static ExecutionTask repair() {
        return ExecutionTask.queueRepair(42L, "OS-000042", SAGA, VEHICLE, ITEMS, 0, NOW);
    }

    @Nested
    class Repair {

        @Test
        void queueRepair_shouldEnterTheQueueAndAnnounceIt() {
            ExecutionTask task = repair();

            assertThat(task.status()).isEqualTo(TaskStatus.QUEUED);
            assertThat(task.queuedAt()).isEqualTo(NOW);
            assertThat(task.version()).isNull();
            assertThat(task.pullEvents()).containsExactly(new ExecutionEvent.Enqueued());
            assertThat(task.pullEvents()).as("eventos são lidos uma vez").isEmpty();
        }

        @Test
        void fullCycle_shouldAnnounceStartAndFinish() {
            ExecutionTask task = repair();
            task.pullEvents();

            task.start("mecanico", NOW.plusMinutes(5));
            task.finish("pastilhas trocadas", NOW.plusHours(1));

            assertThat(task.status()).isEqualTo(TaskStatus.FINISHED);
            assertThat(task.assignee()).isEqualTo("mecanico");
            assertThat(task.outcome()).isEqualTo("pastilhas trocadas");
            assertThat(task.pullEvents()).containsExactly(new ExecutionEvent.Started("mecanico"),
                new ExecutionEvent.Finished("pastilhas trocadas"));
        }

        @Test
        void failure_shouldRequireAReasonAndAStartedTask() {
            ExecutionTask task = repair();
            assertThatThrownBy(() -> task.fail("peça indisponível", NOW)).isInstanceOf(ExecutionRuleViolation.class);

            task.start("mecanico", NOW);
            assertThatThrownBy(() -> task.fail(" ", NOW)).isInstanceOf(ExecutionRuleViolation.class);
            task.fail("peça indisponível", NOW);

            assertThat(task.status()).isEqualTo(TaskStatus.FAILED);
            assertThat(task.pullEvents()).contains(new ExecutionEvent.Failed("peça indisponível"));
        }

        @Test
        void outOfOrderTransitions_shouldBeRejected() {
            ExecutionTask task = repair();

            assertThatThrownBy(() -> task.finish("ok", NOW)).isInstanceOf(ExecutionRuleViolation.class)
                .hasMessageContaining("QUEUED");
            task.start("mecanico", NOW);
            assertThatThrownBy(() -> task.start("outro", NOW)).isInstanceOf(ExecutionRuleViolation.class);
            assertThatThrownBy(() -> task.start(null, NOW)).isInstanceOf(ExecutionRuleViolation.class);
        }

        @Test
        void repairWithoutItems_shouldBeRefused() {
            assertThatThrownBy(() -> ExecutionTask.queueRepair(1L, "OS-000001", SAGA, VEHICLE, List.of(), 0, NOW))
                .isInstanceOf(ExecutionRuleViolation.class);
            assertThatThrownBy(() -> ExecutionTask.queueRepair(1L, "OS-000001", SAGA, VEHICLE, ITEMS, -1, NOW))
                .isInstanceOf(ExecutionRuleViolation.class);
        }
    }

    @Nested
    class SagaCommandsAreIdempotent {

        @Test
        void sameSagaEnqueuingAgain_shouldOnlyRepeatTheConfirmation() {
            ExecutionTask task = repair();
            task.pullEvents();
            task.start("mecanico", NOW);
            task.pullEvents();

            task.requeueForSaga(SAGA, VEHICLE, ITEMS, 0, NOW.plusMinutes(1));

            assertThat(task.status()).isEqualTo(TaskStatus.IN_PROGRESS);
            assertThat(task.pullEvents()).containsExactly(new ExecutionEvent.Enqueued());
        }

        @Test
        void anotherSagaWhileActive_shouldBeRefused() {
            ExecutionTask task = repair();
            assertThatThrownBy(() -> task.requeueForSaga(UUID.randomUUID(), VEHICLE, ITEMS, 0, NOW))
                .isInstanceOf(ExecutionRuleViolation.class).hasMessageContaining("outra Saga");
        }

        @Test
        void newSagaAfterCancellation_shouldReopenTheTask() {
            ExecutionTask task = repair();
            task.cancel("cliente desistiu", NOW);
            UUID newSaga = UUID.randomUUID();

            task.requeueForSaga(newSaga, VEHICLE, ITEMS, 3, NOW.plusDays(1));

            assertThat(task.status()).isEqualTo(TaskStatus.QUEUED);
            assertThat(task.sagaId()).isEqualTo(newSaga);
            assertThat(task.priority()).isEqualTo(3);
            assertThat(task.outcome()).isNull();
        }

        @Test
        void finishedRepair_shouldNotReturnToTheQueue() {
            ExecutionTask task = repair();
            task.start("mecanico", NOW);
            task.finish("ok", NOW);

            assertThatThrownBy(() -> task.requeueForSaga(UUID.randomUUID(), VEHICLE, ITEMS, 0, NOW))
                .isInstanceOf(ExecutionRuleViolation.class).hasMessageContaining("concluído");
        }

        @Test
        void cancel_shouldReportAlreadyFinishedInsteadOfUndoingWork() {
            ExecutionTask finished = repair();
            finished.start("mecanico", NOW);
            finished.finish("ok", NOW);
            finished.pullEvents();

            finished.cancel("tarde demais", NOW);

            assertThat(finished.status()).isEqualTo(TaskStatus.FINISHED);
            assertThat(finished.pullEvents()).containsExactly(new ExecutionEvent.Cancelled(true));
        }

        @Test
        void cancelTwice_shouldConfirmBothTimes() {
            ExecutionTask task = repair();
            task.pullEvents();
            task.cancel("x", NOW);
            task.cancel("x", NOW);

            assertThat(task.status()).isEqualTo(TaskStatus.CANCELLED);
            assertThat(task.pullEvents()).containsExactly(new ExecutionEvent.Cancelled(false),
                new ExecutionEvent.Cancelled(false));
        }
    }

    @Nested
    class Diagnosis {

        @Test
        void diagnosis_shouldNotTalkToTheSagaExceptThroughNotes() {
            ExecutionTask task = ExecutionTask.openDiagnosis(7L, "OS-000007", VEHICLE, NOW);
            task.start("mecanico", NOW);
            TechnicalNote note = task.addNote("mecanico", "disco empenado", TechnicalNote.Kind.FINDING, NOW);
            task.finish(null, NOW);

            assertThat(task.pullEvents()).containsExactly(new ExecutionEvent.Progress(Phase.DIAGNOSIS, "disco empenado"));
            assertThat(task.newNotes()).containsExactly(note);
            assertThat(task.outcome()).isEmpty();
        }

        @Test
        void failure_shouldBeExclusiveToRepair() {
            ExecutionTask task = ExecutionTask.openDiagnosis(7L, "OS-000007", VEHICLE, NOW);
            task.start("mecanico", NOW);
            assertThatThrownBy(() -> task.fail("x", NOW)).isInstanceOf(ExecutionRuleViolation.class);
            assertThatThrownBy(() -> task.requeueForSaga(SAGA, VEHICLE, ITEMS, 0, NOW))
                .isInstanceOf(ExecutionRuleViolation.class);
        }

        @Test
        void reopen_shouldOnlyResetAClosedDiagnosis() {
            ExecutionTask task = ExecutionTask.openDiagnosis(7L, "OS-000007", VEHICLE, NOW);
            task.start("mecanico", NOW);
            task.reopenDiagnosis(NOW);
            assertThat(task.status()).isEqualTo(TaskStatus.IN_PROGRESS);

            task.finish("ok", NOW);
            task.reopenDiagnosis(NOW.plusDays(1));
            assertThat(task.status()).isEqualTo(TaskStatus.QUEUED);
            assertThat(task.assignee()).isNull();
            assertThatThrownBy(() -> repair().reopenDiagnosis(NOW)).isInstanceOf(ExecutionRuleViolation.class);
        }

        @Test
        void closedTask_shouldNotAcceptNotes() {
            ExecutionTask task = repair();
            task.cancel("x", NOW);
            assertThatThrownBy(() -> task.addNote("mecanico", "nota", null, NOW)).isInstanceOf(ExecutionRuleViolation.class);
        }
    }

    @Test
    void valueObjects_shouldRejectIncompleteData() {
        assertThatThrownBy(() -> new VehicleInfo("Fiat", " ", "ABC1D23")).isInstanceOf(ExecutionRuleViolation.class);
        assertThatThrownBy(() -> new VehicleInfo(null, "Argo", "ABC1D23")).isInstanceOf(ExecutionRuleViolation.class);
        assertThatThrownBy(() -> new VehicleInfo("Fiat", "Argo", null)).isInstanceOf(ExecutionRuleViolation.class);
        assertThatThrownBy(() -> new WorkItem(null, "Pastilha", 1)).isInstanceOf(ExecutionRuleViolation.class);
        assertThatThrownBy(() -> new WorkItem(WorkItem.Kind.PART, null, 1)).isInstanceOf(ExecutionRuleViolation.class);
        assertThatThrownBy(() -> new WorkItem(WorkItem.Kind.PART, " ", 1)).isInstanceOf(ExecutionRuleViolation.class);
        assertThatThrownBy(() -> new TechnicalNote(UUID.randomUUID(), Phase.REPAIR, "m", null,
            TechnicalNote.Kind.OBSERVATION, NOW)).isInstanceOf(ExecutionRuleViolation.class);
        assertThatThrownBy(() -> new TechnicalNote(UUID.randomUUID(), Phase.REPAIR, null, "nota",
            TechnicalNote.Kind.OBSERVATION, NOW)).isInstanceOf(ExecutionRuleViolation.class);
        assertThatThrownBy(() -> new WorkItem(WorkItem.Kind.PART, "Pastilha", 0)).isInstanceOf(ExecutionRuleViolation.class);
        assertThatThrownBy(() -> new TechnicalNote(UUID.randomUUID(), Phase.REPAIR, "m", "x".repeat(1001),
            TechnicalNote.Kind.OBSERVATION, NOW)).isInstanceOf(ExecutionRuleViolation.class);
        assertThatThrownBy(() -> new TechnicalNote(UUID.randomUUID(), Phase.REPAIR, " ", "nota",
            TechnicalNote.Kind.OBSERVATION, NOW)).isInstanceOf(ExecutionRuleViolation.class);
    }
}
