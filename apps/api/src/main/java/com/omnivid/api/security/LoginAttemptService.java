package com.omnivid.api.security;

import jakarta.servlet.http.HttpServletRequest;

public interface LoginAttemptService {
    void requireAllowed(HttpServletRequest request, String email);

    LoginAttemptStatus recordFailure(HttpServletRequest request, String email);

    void clear(HttpServletRequest request, String email);
}
