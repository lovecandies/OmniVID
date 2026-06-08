package com.omnivid.api.agent.memory;

import java.time.Duration;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "omnivid.agent-short-term-memory", name = "mode", havingValue = "redis")
public class RedisAgentShortTermMemory implements AgentShortTermMemory {
    private static final Duration TTL = Duration.ofMinutes(20);

    private final StringRedisTemplate redis;

    public RedisAgentShortTermMemory(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Optional<String> getLastQuestion(long videoId) {
        return Optional.ofNullable(redis.opsForValue().get(key(videoId)));
    }

    @Override
    public void rememberLastQuestion(long videoId, String question) {
        redis.opsForValue().set(key(videoId), question, TTL);
    }

    @Override
    public void clear(long videoId) {
        redis.delete(key(videoId));
    }

    @Override
    public String source() {
        return "redis";
    }

    private String key(long videoId) {
        return "omnivid:agent:memory:last-question:" + videoId;
    }
}
