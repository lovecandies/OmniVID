package com.omnivid.api.security;

import com.omnivid.api.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "omnivid.security.login-rate-limit", name = "mode", havingValue = "local", matchIfMissing = true)
public class LocalLoginAttemptService implements LoginAttemptService {
    private final ConcurrentHashMap<String, AttemptWindow> attempts = new ConcurrentHashMap<>();
    private final int maxFailures;
    private final int captchaThreshold;
    private final Duration window;
    private final ClientIpResolver clientIps;

    public LocalLoginAttemptService(
            @Value("${omnivid.security.login-rate-limit.max-failures:5}") int maxFailures,
            @Value("${omnivid.security.login-rate-limit.captcha-threshold:3}") int captchaThreshold,
            @Value("${omnivid.security.login-rate-limit.window:10m}") Duration window,
            ClientIpResolver clientIps
    ) {
        this.maxFailures = Math.max(1, maxFailures);
        this.captchaThreshold = Math.max(1, Math.min(captchaThreshold, this.maxFailures));
        this.window = window;
        this.clientIps = clientIps;
    }

    @Override
    public void requireAllowed(HttpServletRequest request, String email) {
        String key = LoginAttemptKey.from(clientIps.resolve(request), email);
        AttemptWindow current = attempts.get(key);
        if (current == null || current.expiresAt().isBefore(Instant.now())) {
            attempts.remove(key);
            return;
        }
        if (current.failures() >= maxFailures) {
            throw blocked(current);
        }
    }

    @Override
    public LoginAttemptStatus recordFailure(HttpServletRequest request, String email) {
        Instant now = Instant.now();
        AttemptWindow updated = attempts.compute(LoginAttemptKey.from(clientIps.resolve(request), email), (key, current) -> {
            if (current == null || current.expiresAt().isBefore(now)) {
                return new AttemptWindow(1, now.plus(window));
            }
            return new AttemptWindow(current.failures() + 1, current.expiresAt());
        });
        return status(updated);
    }

    @Override
    public void clear(HttpServletRequest request, String email) {
        attempts.remove(LoginAttemptKey.from(clientIps.resolve(request), email));
    }

    private ApiException blocked(AttemptWindow current) {
        return new ApiException(
                HttpStatus.TOO_MANY_REQUESTS,
                "Too many failed login attempts",
                "Wait before retrying or verify the email and password",
                status(current).detail()
        );
    }

    private LoginAttemptStatus status(AttemptWindow current) {
        long retryAfter = Math.max(0, Duration.between(Instant.now(), current.expiresAt()).toSeconds());
        return new LoginAttemptStatus(
                current.failures(),
                maxFailures,
                current.failures() >= captchaThreshold,
                retryAfter
        );
    }

    private record AttemptWindow(int failures, Instant expiresAt) {
    }
}
