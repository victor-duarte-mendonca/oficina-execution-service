package br.com.oficina.execution.infrastructure.observability;

import br.com.oficina.execution.infrastructure.configuration.ExecutionConfig;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.TableStatus;

/**
 * Pronto para tráfego só com a tabela disponível: sem ela, toda requisição e todo comando falhariam. O
 * {@code DescribeTable} é operação de controle (sem custo de leitura) e a probe roda a cada 10 s.
 */
@Readiness
@ApplicationScoped
public class DynamoReadinessCheck implements HealthCheck {

    private final DynamoDbClient dynamo;
    private final String table;

    public DynamoReadinessCheck(DynamoDbClient dynamo, ExecutionConfig config) {
        this.dynamo = dynamo;
        this.table = config.dynamodb().table();
    }

    @Override
    public HealthCheckResponse call() {
        try {
            TableStatus status = dynamo.describeTable(request -> request.tableName(table)).table().tableStatus();
            return HealthCheckResponse.named("dynamodb")
                .status(status == TableStatus.ACTIVE)
                .withData("table", table)
                .withData("status", status.toString())
                .build();
        } catch (RuntimeException e) {
            return HealthCheckResponse.named("dynamodb").down()
                .withData("table", table)
                .withData("error", e.getClass().getSimpleName())
                .build();
        }
    }
}
