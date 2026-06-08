package com.omnivid.api.agent;

public record AgentCitation(
        String citation,
        long videoId,
        long segmentId,
        long startMs,
        long endMs,
        int score,
        String snippet
) {
}
