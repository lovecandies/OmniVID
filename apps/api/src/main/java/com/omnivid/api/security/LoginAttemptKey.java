package com.omnivid.api.security;

import java.util.Locale;

final class LoginAttemptKey {
    private LoginAttemptKey() {
    }

    static String from(String ip, String email) {
        String normalizedEmail = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        return ip + "|" + normalizedEmail;
    }
}
