package br.com.oficina.execution.testsupport;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.util.Map;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.containers.localstack.LocalStackContainer.Service;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/**
 * DynamoDB e SQS reais (LocalStack) para os testes {@code @QuarkusTest}: sobe o contêiner, cria as filas
 * FIFO e aponta a aplicação para ele. As credenciais de teste vão pelas propriedades {@code aws.*}, que a
 * cadeia padrão do SDK lê — o mesmo caminho de produção, só que com outro provedor.
 */
public class LocalStackResource implements QuarkusTestResourceLifecycleManager {

    public static final String COMMANDS_QUEUE = "oficina-test-execution-commands.fifo";
    public static final String REPLIES_QUEUE = "oficina-test-os-saga-replies.fifo";

    private LocalStackContainer localstack;

    @Override
    public Map<String, String> start() {
        localstack = new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.8.1"))
            .withServices(Service.DYNAMODB, Service.SQS);
        localstack.start();
        System.setProperty("aws.accessKeyId", localstack.getAccessKey());
        System.setProperty("aws.secretAccessKey", localstack.getSecretKey());

        String commands;
        String replies;
        try (SqsClient sqs = SqsClient.builder()
                .endpointOverride(localstack.getEndpoint())
                .region(Region.of(localstack.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(localstack.getAccessKey(), localstack.getSecretKey())))
                .httpClient(UrlConnectionHttpClient.create())
                .build()) {
            commands = fifo(sqs, COMMANDS_QUEUE);
            replies = fifo(sqs, REPLIES_QUEUE);
        }
        return Map.of(
            "app.aws.region", localstack.getRegion(),
            "app.aws.endpoint-override", localstack.getEndpoint().toString(),
            "app.messaging.enabled", "true",
            "app.messaging.wait-seconds", "1",
            "app.messaging.queues.commands", commands,
            "app.messaging.queues.os-replies", replies);
    }

    @Override
    public void stop() {
        if (localstack != null) {
            localstack.stop();
        }
    }

    private static String fifo(SqsClient sqs, String name) {
        return sqs.createQueue(request -> request.queueName(name)
                .attributes(Map.of(QueueAttributeName.FIFO_QUEUE, "true", QueueAttributeName.VISIBILITY_TIMEOUT, "2")))
            .queueUrl();
    }
}
