package br.com.oficina.execution.domain.model;

/**
 * Fase do trabalho na oficina (spec 06 §2). {@code DIAGNOSIS} é informativa (o mecânico registra o que
 * encontrou; não é passo da Saga); {@code REPAIR} nasce do pagamento confirmado e responde à Saga.
 */
public enum Phase {
    DIAGNOSIS,
    REPAIR;

    public boolean answersToSaga() {
        return this == REPAIR;
    }
}
