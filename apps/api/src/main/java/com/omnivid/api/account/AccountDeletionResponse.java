package com.omnivid.api.account;

public record AccountDeletionResponse(
        long userId,
        boolean deleted,
        int invalidatedSessions
) {
}
