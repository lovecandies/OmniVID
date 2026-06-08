package com.omnivid.api.llm;

public record CloudLlmTestResponse(
        boolean success,
        String message,
        String model,
        long durationMs,
        int promptTokens,
        int completionTokens,
        int totalTokens
) {
}
