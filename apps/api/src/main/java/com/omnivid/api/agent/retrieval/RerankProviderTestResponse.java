package com.omnivid.api.agent.retrieval;

public record RerankProviderTestResponse(
        boolean success,
        String message,
        String providerName,
        String model
) {
}
