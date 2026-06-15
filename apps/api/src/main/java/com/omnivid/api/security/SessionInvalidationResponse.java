package com.omnivid.api.security;

public record SessionInvalidationResponse(
        long userId,
        String email,
        int invalidatedSessions
) {
}
