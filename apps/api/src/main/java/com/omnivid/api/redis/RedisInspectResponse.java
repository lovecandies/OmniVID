package com.omnivid.api.redis;

import java.util.List;

public record RedisInspectResponse(
        boolean connected,
        List<RedisKeyInspectItem> keys
) {
}
