package com.omnivid.api.ratelimit;

import java.time.Duration;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "omnivid.agent-rate-limit", name = "mode", havingValue = "redis")
public class RedisAgentRateLimiter implements AgentRateLimiter {
    private static final int LIMIT = 5;
    private static final long WINDOW_SECONDS = 10;

    private final StringRedisTemplate redis;

    public RedisAgentRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public boolean allow(String scope) {
        long window = Instant.now().getEpochSecond() / WINDOW_SECONDS;
        String key = "omnivid:agent:rate:" + scope + ":" + window;
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redis.expire(key, Duration.ofSeconds(WINDOW_SECONDS + 2));
        }
        return count != null && count <= LIMIT;
    }
}
