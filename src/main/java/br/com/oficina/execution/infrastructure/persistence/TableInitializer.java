package br.com.oficina.execution.infrastructure.persistence;

import br.com.oficina.execution.infrastructure.configuration.ExecutionConfig;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.jboss.logging.Logger;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/** Em dev, docker compose e testes ({@code app.dynamodb.create-table=true}) a tabela nasce com a aplicação. */
@ApplicationScoped
public class TableInitializer {

    private static final Logger LOG = Logger.getLogger(TableInitializer.class);

    void onStart(@Observes StartupEvent event, ExecutionConfig config, DynamoDbClient dynamo) {
        if (config.dynamodb().createTable()) {
            ExecutionTable.createIfMissing(dynamo, config.dynamodb().table());
            LOG.infof("Tabela DynamoDB %s pronta", config.dynamodb().table());
        }
    }
}
