package com.omnivid.api.agent.retrieval;

public record EmbeddingProviderConfig(
        long id,
        long userId,
        String providerName,
        String mode,
        String baseUrl,
        String model,
        String apiKeyEncoded,
        String apiKeyMasked,
        int timeoutSeconds,
        boolean enabled,
        boolean active,
        String lastTestStatus,
        String lastTestMessage
) {
}
