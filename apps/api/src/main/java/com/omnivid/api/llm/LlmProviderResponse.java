package com.omnivid.api.llm;

public record LlmProviderResponse(
        long id,
        String providerName,
        String baseUrl,
        String model,
        String apiKeyMasked,
        int timeoutSeconds,
        boolean enabled,
        boolean active,
        String lastTestStatus,
        String lastTestMessage
) {
    static LlmProviderResponse from(LlmProviderConfig config) {
        return new LlmProviderResponse(
                config.id(),
                config.providerName(),
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
