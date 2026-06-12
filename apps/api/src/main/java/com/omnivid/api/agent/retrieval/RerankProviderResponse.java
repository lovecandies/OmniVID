package com.omnivid.api.agent.retrieval;

public record RerankProviderResponse(
        long id,
        String providerName,
        String mode,
        String baseUrl,
        String endpoint,
        String model,
        String apiKeyMasked,
        int timeoutSeconds,
        boolean enabled,
        boolean active,
        String lastTestStatus,
        String lastTestMessage
) {
    static RerankProviderResponse from(RerankProviderConfig config) {
        return new RerankProviderResponse(
                config.id(),
                config.providerName(),
                config.mode(),
                config.baseUrl(),
                config.endpoint(),
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
