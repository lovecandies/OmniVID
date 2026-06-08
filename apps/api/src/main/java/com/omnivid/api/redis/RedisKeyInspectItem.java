package com.omnivid.api.redis;

public record RedisKeyInspectItem(
        String hook,
        String pattern,
        String sampleKey,
        String type,
        long ttlSeconds,
        boolean exists,
        String note
) {
}
