package com.omnivid.api.llm;

import java.util.List;

public record CloudEmbeddingResult(
        List<Double> vector,
        String model,
        long durationMs,
        int promptTokens,
        int totalTokens
) {
    public CloudEmbeddingResult {
        vector = vector == null ? List.of() : List.copyOf(vector);
    }
}
