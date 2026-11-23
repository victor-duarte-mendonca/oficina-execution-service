package br.com.oficina.execution.infrastructure.configuration;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.net.URI;
import java.util.Optional;

/**
 * Configuração de infraestrutura ({@code app.*}). Nomes e URLs vêm do contrato SSM publicado pelos
 * repositórios de infraestrutura (spec 12 §1); em dev/teste apontam para o LocalStack.
 */
@ConfigMapping(prefix = "app")
public interface ExecutionConfig {

    Aws aws();

    Dynamodb dynamodb();

    Messaging messaging();

    Gateway gateway();

    interface Gateway {
        /** Chave que o API Gateway injeta em {@code X-Gateway-Key}; vazia desliga o filtro (dev/test/docker). */
        Optional<String> key();
    }

    interface Aws {
        @WithDefault("us-east-1")
        String region();

        /** LocalStack em dev/teste; vazio = endpoints públicos da AWS. */
        Optional<URI> endpointOverride();
    }

    interface Dynamodb {
        @WithDefault("oficina-local-execution")
        String table();

        /** Cria a tabela na subida se não existir — só dev/teste; em AWS ela é do Terraform. */
        @WithDefault("false")
        boolean createTable();
    }

    interface Messaging {
        @WithDefault("false")
        boolean enabled();

        Queues queues();

        @WithDefault("10")
        int maxMessages();

        @WithDefault("10")
        int waitSeconds();

        @WithDefault("25")
        int relayBatchSize();

        interface Queues {
            /** Fila de entrada: {@code execution-commands.fifo}. */
            Optional<String> commands();

            /** Fila de saída: {@code os-saga-replies.fifo}. */
            Optional<String> osReplies();
        }
    }
}
