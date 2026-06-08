package com.omnivid.api.agent;

import java.util.List;

public record AgentContextResponse(
        long videoId,
        int windowLimit,
        int messageCount,
        boolean contextReady,
        String lastUserQuestion,
        String shortTermQuestion,
        String memorySource,
        List<ChatMessage> messages
) {
    public AgentContextResponse {
        lastUserQuestion = lastUserQuestion == null ? "" : lastUserQuestion;
        shortTermQuestion = shortTermQuestion == null ? "" : shortTermQuestion;
        memorySource = memorySource == null ? "" : memorySource;
        messages = messages == null ? List.of() : List.copyOf(messages);
    }
}
