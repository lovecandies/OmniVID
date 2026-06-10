package com.omnivid.api.agent.retrieval;

public record EmbeddingProviderTestResponse(
        boolean success,
        String message,
        String provider,
        String model,
        int dimensions
) {
}
