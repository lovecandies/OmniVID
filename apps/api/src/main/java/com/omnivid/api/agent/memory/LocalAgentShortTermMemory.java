package com.omnivid.api.agent.memory;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "omnivid.agent-short-term-memory", name = "mode", havingValue = "local", matchIfMissing = true)
public class LocalAgentShortTermMemory implements AgentShortTermMemory {
    private final ConcurrentHashMap<Long, String> lastQuestions = new ConcurrentHashMap<>();

    @Override
    public Optional<String> getLastQuestion(long videoId) {
        return Optional.ofNullable(lastQuestions.get(videoId));
    }

    @Override
    public void rememberLastQuestion(long videoId, String question) {
        lastQuestions.put(videoId, question);
    }

    @Override
    public void clear(long videoId) {
        lastQuestions.remove(videoId);
    }

    @Override
    public String source() {
        return "local";
    }
}
