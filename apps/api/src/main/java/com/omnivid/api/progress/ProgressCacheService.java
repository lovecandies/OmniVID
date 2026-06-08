package com.omnivid.api.progress;

import java.util.Optional;

public interface ProgressCacheService {
    void put(ProgressSnapshot snapshot);

    Optional<ProgressSnapshot> get(long videoId);
}
