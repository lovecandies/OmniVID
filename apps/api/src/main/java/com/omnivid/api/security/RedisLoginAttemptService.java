package com.omnivid.api.security;

import com.omnivid.api.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "omnivid.security.login-rate-limit", name = "mode", havingValue = "redis")
public class RedisLoginAttemptService implements LoginAttemptService {
    private static final String PREFIX = "omnivid:security:login-fail:";

    private final StringRedisTemplate redis;
    private final int maxFailures;
    private final int captchaThreshold;
    private final Duration window;
    private final ClientIpResolver clientIps;

    public RedisLoginAttemptService(
            StringRedisTemplate redis,
            @Value("${omnivid.security.login-rate-limit.max-failures:5}") int maxFailures,
            @Value("${omnivid.security.login-rate-limit.captcha-threshold:3}") int captchaThreshold,
            @Value("${omnivid.security.login-rate-limit.window:10m}") Duration window,
            ClientIpResolver clientIps
    ) {
        this.redis = redis;
        this.maxFailures = Math.max(1, maxFailures);
        this.captchaThreshold = Math.max(1, Math.min(captchaThreshold, this.maxFailures));
        this.window = window;
        this.clientIps = clientIps;
    }

    @Override
    public void requireAllowed(HttpServletRequest request, String email) {
        LoginAttemptStatus status = status(key(request, email));
        if (status.failures() >= maxFailures) {
            throw new ApiException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Too many failed login attempts",
                    "Wait before retrying or verify the email and password",
                    status.detail()
            );
        }
    }

    @Override
    public LoginAttemptStatus recordFailure(HttpServletRequest request, String email) {
        String key = key(request, email);
        Long failures = redis.opsForValue().increment(key);
        if (failures != null && failures == 1L) {
            redis.expire(key, window);
        }
        return status(key, failures == null ? 0 : failures.intValue());
    }

    @Override
    public void clear(HttpServletRequest request, String email) {
        redis.delete(key(request, email));
    }

    private LoginAttemptStatus status(String key) {
        String value = redis.opsForValue().get(key);
        return status(key, value == null ? 0 : Integer.parseInt(value));
    }

    private LoginAttemptStatus status(String key, int failures) {
        Long ttl = redis.getExpire(key);
        long retryAfter = ttl == null || ttl < 0 ? 0 : ttl;
        return new LoginAttemptStatus(
                failures,
                maxFailures,
                failures >= captchaThreshold,
                retryAfter
        );
    }

    private String key(HttpServletRequest request, String email) {
        return PREFIX + LoginAttemptKey.from(clientIps.resolve(request), email);
    }
}
