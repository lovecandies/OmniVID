package com.omnivid.api.agent.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnivid.api.agent.AgentAskResponse;
import java.time.Duration;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "omnivid.agent-answer-cache", name = "mode", havingValue = "redis")
public class RedisAgentAnswerCache implements AgentAnswerCache {
    private static final Duration TTL = Duration.ofMinutes(30);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public RedisAgentAnswerCache(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<AgentAskResponse> get(String scope, String question) {
        String value = redis.opsForValue().get(key(scope, question));
        if (value == null) {
            return Optional.empty();
        }
        try {
            AgentAskResponse response = objectMapper.readValue(value, AgentAskResponse.class);
            return Optional.of(response.withCacheHit(true));
        } catch (JsonProcessingException exception) {
            redis.delete(key(scope, question));
            return Optional.empty();
        }
    }

    @Override
    public void put(String scope, String question, AgentAskResponse response) {
        try {
            redis.opsForValue().set(key(scope, question), objectMapper.writeValueAsString(response.withCacheHit(false)), TTL);
        } catch (JsonProcessingException ignored) {
            // Cache failure must not break Agent answers.
        }
    }

    private String key(String scope, String question) {
        return "omnivid:agent:semantic:" + scope + ":" + Integer.toHexString(question.trim().toLowerCase().hashCode());
    }
}
