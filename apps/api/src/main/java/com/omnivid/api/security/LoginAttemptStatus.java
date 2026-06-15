package com.omnivid.api.security;

public record LoginAttemptStatus(
        int failures,
        int maxFailures,
        boolean captchaRequired,
        long retryAfterSeconds
) {
    public String detail() {
        return "failures=%d; maxFailures=%d; captchaRequired=%s; retryAfterSeconds=%d".formatted(
                failures,
                maxFailures,
                captchaRequired,
                retryAfterSeconds
        );
    }
}
