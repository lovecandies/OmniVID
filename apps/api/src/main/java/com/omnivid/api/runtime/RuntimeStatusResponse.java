package com.omnivid.api.runtime;

import com.omnivid.api.job.mq.ProcessingMqStatusResponse;

public record RuntimeStatusResponse(
        String profile,
        RuntimeDatabaseStatus database,
        RuntimeRedisStatus redis,
        ProcessingMqStatusResponse processing,
        RuntimeObservabilityStatus observability,
        RuntimeLlmStatus llm
) {
    public record RuntimeDatabaseStatus(
            boolean connected,
            String product,
            String url,
            String hook
    ) {
    }

    public record RuntimeRedisStatus(
            boolean connected,
            String dedupeLockMode,
            String progressCacheMode,
            String rateLimitMode,
            String answerCacheMode,
            String shortTermMemoryMode
    ) {
    }

    public record RuntimeObservabilityStatus(
            String logFormat,
            String traceHeader,
            String traceId,
            String hook
    ) {
    }

    public record RuntimeLlmStatus(
            boolean chatEnabled,
            boolean chatConfigured,
            String baseUrl,
            String model,
            String embeddingProvider,
            String embeddingDiagnostic,
            String embeddingIndex,
            int embeddingDimensions,
            String vectorStoreMode,
            boolean vectorStoreConnected,
            String vectorStoreEndpoint,
            String rerankProvider,
            String rerankDiagnostic
    ) {
    }
}
