package com.omnivid.api.agent.memory;

import java.util.Optional;

public interface AgentShortTermMemory {
    Optional<String> getLastQuestion(long videoId);

    void rememberLastQuestion(long videoId, String question);

    void clear(long videoId);

    String source();
}
