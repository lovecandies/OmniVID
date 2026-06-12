package com.omnivid.api.knowledge;

import java.util.List;

public record KnowledgeBaseCoverageResponse(
        KnowledgeBase knowledgeBase,
        int videoCount,
        int readyVideoCount,
        int transcriptCount,
        long totalDurationMs,
        String summary,
        List<KnowledgeBaseCoverageVideoResponse> videos
) {
}
