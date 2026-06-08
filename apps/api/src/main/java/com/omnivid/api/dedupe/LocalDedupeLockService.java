package com.omnivid.api.dedupe;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "omnivid.dedupe-lock", name = "mode", havingValue = "local", matchIfMissing = true)
public class LocalDedupeLockService implements DedupeLockService {
    private final Set<String> locks = ConcurrentHashMap.newKeySet();

    @Override
    public DedupeLock tryLock(String md5, Duration ttl) {
        String key = "video:lock:" + md5;
        boolean acquired = locks.add(key);
        return new LocalDedupeLock(key, acquired);
    }

    private class LocalDedupeLock implements DedupeLock {
        private final String key;
        private final boolean acquired;

        LocalDedupeLock(String key, boolean acquired) {
            this.key = key;
            this.acquired = acquired;
        }

        @Override
        public boolean acquired() {
            return acquired;
        }

        @Override
        public void close() {
            if (acquired) {
                locks.remove(key);
            }
        }
    }
}
