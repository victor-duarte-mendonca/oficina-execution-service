package br.com.oficina.execution.interfaces.rest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;

import br.com.oficina.execution.application.messaging.InboundMessage;
import br.com.oficina.execution.application.messaging.MessageKind;
import br.com.oficina.execution.application.ports.in.HandleExecutionCommandUseCase;
import br.com.oficina.execution.testsupport.LocalStackResource;
import br.com.oficina.execution.testsupport.TestTokens;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * API do mecânico com tokens reais e DynamoDB do LocalStack: o ciclo de uma tarefa, as recusas de
 * regra (422), os papéis (401/403) e o 404 de OS sem tarefa.
 */
@QuarkusTest
@QuarkusTestResource(LocalStackResource.class)
class ExecutionResourceTest {

    private static final AtomicLong WORK_ORDERS = new AtomicLong(5_000);

    @Inject HandleExecutionCommandUseCase commands;

    @Test
    void repairCycle_shouldBeDrivenByTheMechanic() {
        long workOrderId = queuedRepair();

        asStaff("MECHANIC").when().post("/admin/executions/{id}/start", workOrderId)
            .then().statusCode(200).body("status", equalTo("IN_PROGRESS")).body("assignee", equalTo("staff-mechanic"));
        asStaff("MECHANIC").body("""
                {"text": "pastilhas removidas", "kind": "PART_REPLACED"}
                """)
            .when().post("/admin/executions/{id}/notes", workOrderId).then().statusCode(200);
        asStaff("MECHANIC").body("""
                {"summary": "pastilhas trocadas"}
                """)
            .when().post("/admin/executions/{id}/finish", workOrderId)
            .then().statusCode(200).body("status", equalTo("FINISHED")).body("outcome", equalTo("pastilhas trocadas"));

        asStaff("ADMIN").when().get("/admin/executions/{id}", workOrderId)
            .then().statusCode(200)
            .body("tasks[0].phase", equalTo("REPAIR"))
            .body("notes[0].author", equalTo("staff-mechanic"))
            .body("notes[0].kind", equalTo("PART_REPLACED"));
    }

    @Test
    void failure_shouldRequireAReason() {
        long workOrderId = queuedRepair();
        asStaff("MECHANIC").when().post("/admin/executions/{id}/start", workOrderId).then().statusCode(200);

        asStaff("MECHANIC").body("{}").when().post("/admin/executions/{id}/fail", workOrderId).then().statusCode(400);
        asStaff("MECHANIC").body("""
                {"reason": "peça indisponível"}
                """)
            .when().post("/admin/executions/{id}/fail", workOrderId)
            .then().statusCode(200).body("status", equalTo("FAILED"));
    }

    @Test
    void invalidTransition_shouldBeUnprocessable() {
        long workOrderId = queuedRepair();

        asStaff("MECHANIC").body("{}").when().post("/admin/executions/{id}/finish", workOrderId)
            .then().statusCode(422).body("message", containsString("QUEUED")).body("correlationId", notNullValue());
    }

    @Test
    void queue_shouldListTheQueuedRepairs() {
        long workOrderId = queuedRepair();

        asStaff("MECHANIC").when().get("/admin/executions?phase=REPAIR&status=QUEUED&limit=100")
            .then().statusCode(200).body("workOrderId", hasItem((int) workOrderId));
    }

    @Test
    void unknownWorkOrder_shouldBeNotFound() {
        asStaff("ADMIN").when().get("/admin/executions/{id}", 987_654_321L).then().statusCode(404);
        asStaff("MECHANIC").when().post("/admin/executions/{id}/start", 987_654_321L).then().statusCode(404);
    }

    @Test
    void roles_shouldBeEnforced() {
        given().when().get("/admin/executions").then().statusCode(401);
        given().auth().oauth2(TestTokens.client(1L)).when().get("/admin/executions").then().statusCode(403);
    }

    @Test
    void correlationId_shouldBeEchoed() {
        asStaff("ADMIN").header("X-Correlation-Id", "req-echo-1").when().get("/admin/executions")
            .then().statusCode(200).header("X-Correlation-Id", equalTo("req-echo-1"));
    }

    @Test
    void readiness_shouldCheckTheTable() {
        given().when().get("/q/health/ready").then().statusCode(200)
            .body("checks.name", hasItem("dynamodb"))
            .body("checks[0].data.status", equalTo("ACTIVE"));
    }

    private long queuedRepair() {
        long workOrderId = WORK_ORDERS.incrementAndGet();
        commands.handle(InboundMessage.of(UUID.randomUUID(), "EnqueueExecution", MessageKind.COMMAND, UUID.randomUUID(),
            workOrderId, "OS-%06d".formatted(workOrderId), Map.of(
                "vehicle", Map.of("brand", "Fiat", "model", "Argo", "plate", "ABC1D23"),
                "items", List.of(Map.of("kind", "SERVICE", "name", "Troca de pastilhas", "quantity", 1)),
                "priority", 0)));
        return workOrderId;
    }

    private static RequestSpecification asStaff(String role) {
        return given().auth().oauth2(TestTokens.staff(role)).contentType(ContentType.JSON);
    }
}
