package com.omnivid.api.llm;

public record LlmProviderConfig(
        long id,
        long userId,
        String providerName,
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
