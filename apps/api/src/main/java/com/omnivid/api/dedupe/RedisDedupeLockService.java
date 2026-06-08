package com.omnivid.api.dedupe;

import java.time.Duration;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "omnivid.dedupe-lock", name = "mode", havingValue = "redis")
public class RedisDedupeLockService implements DedupeLockService {
    private final StringRedisTemplate redis;

    public RedisDedupeLockService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public DedupeLock tryLock(String md5, Duration ttl) {
        String key = "video:lock:" + md5;
        String token = UUID.randomUUID().toString();
        Boolean acquired = redis.opsForValue().setIfAbsent(key, token, ttl);
        return new RedisDedupeLock(key, token, Boolean.TRUE.equals(acquired));
    }

    private class RedisDedupeLock implements DedupeLock {
        private final String key;
        private final String token;
        private final boolean acquired;

        RedisDedupeLock(String key, String token, boolean acquired) {
            this.key = key;
            this.token = token;
            this.acquired = acquired;
        }

        @Override
        public boolean acquired() {
            return acquired;
        }

        @Override
        public void close() {
            if (acquired && token.equals(redis.opsForValue().get(key))) {
                redis.delete(key);
            }
        }
    }
}
