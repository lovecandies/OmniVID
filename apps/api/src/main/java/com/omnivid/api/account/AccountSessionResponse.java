package com.omnivid.api.account;

import java.time.Instant;

public record AccountSessionResponse(
        String sessionId,
        String principalName,
        Instant createdAt,
        Instant lastAccessedAt,
        long maxInactiveIntervalSeconds,
        boolean current
) {
}
