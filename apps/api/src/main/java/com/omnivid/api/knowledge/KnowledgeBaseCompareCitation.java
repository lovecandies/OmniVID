package com.omnivid.api.knowledge;

public record KnowledgeBaseCompareCitation(
        String citation,
        long videoId,
        long segmentId,
        long startMs,
        long endMs,
        int score,
        String snippet
) {
}
