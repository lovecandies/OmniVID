package com.omnivid.api.llm;

public record CloudLlmConfigResponse(
        boolean enabled,
        boolean configured,
        String baseUrl,
        String model,
        int timeoutSeconds,
        String apiKeyMasked
) {
}
