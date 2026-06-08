package com.omnivid.api.agent.retrieval;

public record VectorIndexRebuildResponse(
        boolean success,
        String vectorStoreMode,
        String indexName,
        int videoCount,
        int segmentCount,
        int indexedCount,
        String message
) {
}
