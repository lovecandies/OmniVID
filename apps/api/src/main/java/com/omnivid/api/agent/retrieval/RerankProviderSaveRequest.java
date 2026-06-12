package com.omnivid.api.agent.retrieval;

public record RerankProviderSaveRequest(
        String providerName,
        String mode,
        String baseUrl,
        String endpoint,
        String model,
        String apiKey,
        Integer timeoutSeconds
) {
}
