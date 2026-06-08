package com.omnivid.api.llm;

public record LlmProviderSaveRequest(
        String providerName,
        String apiKey,
        String baseUrl,
        String model,
        Integer timeoutSeconds
) {
}
