package com.omnivid.api.llm;

public record CloudLlmResult(
        String content,
        String model,
        long durationMs,
        int promptTokens,
        int completionTokens,
        int totalTokens
) {
}
