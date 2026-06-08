package com.omnivid.api.llm;

public record CloudLlmConfigRequest(
        boolean enabled,
        String apiKey,
        String baseUrl,
        String model,
        Integer timeoutSeconds
) {
}
