package com.omnivid.api.account;

public record PasswordChangeResponse(
        long userId,
        String message
) {
}
