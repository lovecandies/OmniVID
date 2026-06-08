package com.omnivid.api.progress;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "omnivid.progress-cache", name = "mode", havingValue = "local", matchIfMissing = true)
public class LocalProgressCacheService implements ProgressCacheService {
    private final ConcurrentHashMap<Long, ProgressSnapshot> cache = new ConcurrentHashMap<>();

    @Override
    public void put(ProgressSnapshot snapshot) {
        cache.put(snapshot.videoId(), snapshot);
    }

    @Override
    public Optional<ProgressSnapshot> get(long videoId) {
        return Optional.ofNullable(cache.get(videoId));
    }
}
