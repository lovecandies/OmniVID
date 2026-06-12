package com.omnivid.api.knowledge;

public record KnowledgeBaseCoverageVideoResponse(
        long videoId,
        String originalName,
        String status,
        long durationMs,
        int transcriptCount,
        long firstStartMs,
        long lastEndMs
) {
}
