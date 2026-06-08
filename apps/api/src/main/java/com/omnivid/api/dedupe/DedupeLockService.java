package com.omnivid.api.dedupe;

import java.time.Duration;

public interface DedupeLockService {
    DedupeLock tryLock(String md5, Duration ttl);
}
