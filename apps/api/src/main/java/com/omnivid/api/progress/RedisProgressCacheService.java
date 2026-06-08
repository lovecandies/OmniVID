package com.omnivid.api.progress;

import java.time.Duration;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "omnivid.progress-cache", name = "mode", havingValue = "redis")
public class RedisProgressCacheService implements ProgressCacheService {
    private final StringRedisTemplate redis;

    public RedisProgressCacheService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void put(ProgressSnapshot snapshot) {
        redis.opsForHash().put(key(snapshot.videoId()), "jobId", String.valueOf(snapshot.jobId()));
        redis.opsForHash().put(key(snapshot.videoId()), "currentStep", snapshot.currentStep());
        redis.opsForHash().put(key(snapshot.videoId()), "status", snapshot.status());
        redis.opsForHash().put(key(snapshot.videoId()), "progress", String.valueOf(snapshot.progress()));
        redis.expire(key(snapshot.videoId()), Duration.ofMinutes(30));
    }

    @Override
    public Optional<ProgressSnapshot> get(long videoId) {
        Object jobId = redis.opsForHash().get(key(videoId), "jobId");
        Object step = redis.opsForHash().get(key(videoId), "currentStep");
        Object status = redis.opsForHash().get(key(videoId), "status");
        Object progress = redis.opsForHash().get(key(videoId), "progress");
        if (jobId == null || step == null || status == null || progress == null) {
            return Optional.empty();
        }
        return Optional.of(new ProgressSnapshot(
                videoId,
                Long.parseLong(jobId.toString()),
                step.toString(),
                status.toString(),
                Integer.parseInt(progress.toString())
        ));
    }

    private String key(long videoId) {
        return "omnivid:progress:" + videoId;
    }
}
