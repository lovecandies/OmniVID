package com.omnivid.api.agent.retrieval;

public record VectorIndexStatusResponse(
        String vectorStoreMode,
        boolean connected,
        String endpoint,
        String collectionName,
        boolean collectionExists,
        String collectionStatus,
        long pointsCount,
        long indexedVectorsCount,
        int segmentsCount,
        int dimensions,
        String distance,
        String message
) {
}
