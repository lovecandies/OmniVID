package com.omnivid.api.agent;

import java.util.List;

public record AgentAskResponse(
        String answer,
        String citation,
        long videoId,
        long startMs,
        long endMs,
        List<AgentCitation> citations,
        int confidenceScore,
        String confidenceLevel,
        boolean contextUsed,
        boolean cacheHit,
        String answerMode,
        List<AgentTraceStep> trace
) {
    public AgentAskResponse {
        citations = citations == null ? List.of() : List.copyOf(citations);
        confidenceLevel = confidenceLevel == null || confidenceLevel.isBlank() ? "NONE" : confidenceLevel;
        answerMode = answerMode == null || answerMode.isBlank() ? "LOCAL_FALLBACK" : answerMode;
        trace = trace == null ? List.of() : List.copyOf(trace);
    }

    public AgentAskResponse withCacheHit(boolean cacheHit) {
        return new AgentAskResponse(
                answer,
                citation,
                videoId,
                startMs,
                endMs,
                citations,
                confidenceScore,
                confidenceLevel,
                contextUsed,
                cacheHit,
                answerMode,
                trace
        );
    }
}
