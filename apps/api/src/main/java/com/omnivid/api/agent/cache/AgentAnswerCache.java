package com.omnivid.api.agent.cache;

import com.omnivid.api.agent.AgentAskResponse;
import java.util.Collection;
import java.util.Optional;

public interface AgentAnswerCache {
    Optional<AgentAskResponse> get(String scope, String question);

    void put(String scope, String question, AgentAskResponse response);

    void evictScope(String scope);

    default void evictScopes(Collection<String> scopes) {
        for (String scope : scopes) {
            evictScope(scope);
        }
    }
}
