package com.omnivid.api.agent.retrieval;

public record EmbeddingProviderResponse(
        long id,
        String providerName,
        String mode,
        String baseUrl,
        String model,
        String apiKeyMasked,
        int timeoutSeconds,
        boolean enabled,
        boolean active,
        String lastTestStatus,
        String lastTestMessage
) {
    static EmbeddingProviderResponse from(EmbeddingProviderConfig config) {
        return new EmbeddingProviderResponse(
                config.id(),
                config.providerName(),
                config.mode(),
                config.baseUrl(),
                config.model(),
                config.apiKeyMasked(),
                config.timeoutSeconds(),
                config.enabled(),
                config.active(),
                config.lastTestStatus(),
                config.lastTestMessage()
        );
    }
}
