package com.omnivid.api.agent.cache;

import com.omnivid.api.agent.AgentAskResponse;
import java.util.Optional;

public interface AgentAnswerCache {
    Optional<AgentAskResponse> get(String scope, String question);

    void put(String scope, String question, AgentAskResponse response);
}
