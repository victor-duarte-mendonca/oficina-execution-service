package br.com.oficina.execution.infrastructure.configuration;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.time.Clock;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.SqsClientBuilder;

/**
 * Clientes AWS e relógio. Credenciais pela cadeia padrão do SDK: no k3s, o IMDS da EC2 (LabRole,
 * ADR 0022); em dev/teste, variáveis ou propriedades {@code aws.*} apontando para o LocalStack. Nenhuma
 * chave é configurada no código. Os clientes só são construídos no primeiro uso.
 */
@ApplicationScoped
public class InfrastructureBeans {

    @Produces
    @Singleton
    Clock clock() {
        return Clock.systemUTC();
    }

    @Produces
    @ApplicationScoped
    DynamoDbClient dynamoDb(ExecutionConfig config) {
        DynamoDbClientBuilder builder = DynamoDbClient.builder()
            .region(Region.of(config.aws().region()))
            .credentialsProvider(DefaultCredentialsProvider.builder().build())
            .httpClient(UrlConnectionHttpClient.create());
        config.aws().endpointOverride().ifPresent(builder::endpointOverride);
        return builder.build();
    }

    @Produces
    @ApplicationScoped
    SqsClient sqs(ExecutionConfig config) {
        SqsClientBuilder builder = SqsClient.builder()
            .region(Region.of(config.aws().region()))
            .credentialsProvider(DefaultCredentialsProvider.builder().build())
            .httpClient(UrlConnectionHttpClient.create());
        config.aws().endpointOverride().ifPresent(builder::endpointOverride);
        return builder.build();
    }

    void closeDynamo(@Disposes DynamoDbClient client) {
        client.close();
    }

    void closeSqs(@Disposes SqsClient client) {
        client.close();
    }
}
