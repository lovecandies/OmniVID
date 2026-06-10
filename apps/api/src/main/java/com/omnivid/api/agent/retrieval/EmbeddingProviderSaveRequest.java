package com.omnivid.api.agent.retrieval;

public record EmbeddingProviderSaveRequest(
        String providerName,
        String mode,
        String baseUrl,
        String model,
        String apiKey,
        Integer timeoutSeconds
) {
}
