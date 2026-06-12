package com.omnivid.api.agent.cache;

import com.omnivid.api.agent.AgentAskResponse;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "omnivid.agent-answer-cache", name = "mode", havingValue = "local", matchIfMissing = true)
public class LocalAgentAnswerCache implements AgentAnswerCache {
    private final ConcurrentHashMap<String, AgentAskResponse> cache = new ConcurrentHashMap<>();

    @Override
    public Optional<AgentAskResponse> get(String scope, String question) {
        return Optional.ofNullable(cache.get(key(scope, question)))
                .map(response -> response.withCacheHit(true));
    }

    @Override
    public void put(String scope, String question, AgentAskResponse response) {
        cache.put(key(scope, question), response.withCacheHit(false));
    }

    @Override
    public void evictScope(String scope) {
        String prefix = scope + ":";
        cache.keySet().removeIf(key -> key.startsWith(prefix));
    }

    private String key(String scope, String question) {
        return scope + ":" + Integer.toHexString(question.trim().toLowerCase().hashCode());
    }
}
