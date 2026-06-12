package com.omnivid.api.agent.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnivid.api.agent.AgentAskResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
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
            String cacheKey = key(scope, question);
            String indexKey = indexKey(scope);
            redis.opsForValue().set(cacheKey, objectMapper.writeValueAsString(response.withCacheHit(false)), TTL);
            redis.opsForSet().add(indexKey, cacheKey);
            redis.expire(indexKey, TTL);
        } catch (JsonProcessingException ignored) {
            // Cache failure must not break Agent answers.
        }
    }

    @Override
    public void evictScope(String scope) {
        String indexKey = indexKey(scope);
        Set<String> keys = redis.opsForSet().members(indexKey);
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
        redis.delete(indexKey);
    }

    private String key(String scope, String question) {
        return prefix(scope) + Integer.toHexString(question.trim().toLowerCase().hashCode());
    }

    private String prefix(String scope) {
        return "omnivid:agent:semantic:" + scope + ":";
    }

    private String indexKey(String scope) {
        return prefix(scope) + "_keys";
    }
}
