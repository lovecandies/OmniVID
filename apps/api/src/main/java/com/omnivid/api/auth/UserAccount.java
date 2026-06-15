package com.omnivid.api.auth;

import java.time.Instant;

public record UserAccount(
        long id,
        String email,
        String passwordHash,
        String nickname,
        boolean emailVerified,
        boolean disabled,
        Instant deletedAt,
        Instant passwordUpdatedAt,
        Instant createdAt,
        Instant updatedAt
) {
}
