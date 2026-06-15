package com.omnivid.api.account;

import java.time.Instant;

public record AccountToken(
        String token,
        long userId,
        String purpose,
        Instant expiresAt,
        Instant consumedAt,
        Instant createdAt
) {
}
