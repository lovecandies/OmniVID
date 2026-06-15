package com.omnivid.api.account;

import java.time.Instant;

public record AccountTokenResponse(
        String purpose,
        String message,
        Instant expiresAt,
        String devToken
) {
}
