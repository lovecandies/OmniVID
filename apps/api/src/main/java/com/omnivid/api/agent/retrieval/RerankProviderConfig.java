package com.omnivid.api.agent.retrieval;

public record RerankProviderConfig(
        long id,
        long userId,
        String providerName,
        String mode,
        String baseUrl,
        String endpoint,
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
