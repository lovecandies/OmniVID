package com.omnivid.api.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnivid.api.observability.TraceContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class ApiRateLimitFilter extends OncePerRequestFilter {
    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final String mode;
    private final int maxRequests;
    private final Duration window;
    private final ClientIpResolver clientIps;
    private final ConcurrentHashMap<String, WindowCounter> localCounters = new ConcurrentHashMap<>();

    public ApiRateLimitFilter(
            ObjectProvider<StringRedisTemplate> redisProvider,
            ObjectMapper objectMapper,
            @Value("${omnivid.security.api-rate-limit.enabled:true}") boolean enabled,
            @Value("${omnivid.security.api-rate-limit.mode:local}") String mode,
            @Value("${omnivid.security.api-rate-limit.max-requests:300}") int maxRequests,
            @Value("${omnivid.security.api-rate-limit.window:1m}") Duration window,
            ClientIpResolver clientIps
    ) {
        this.redisProvider = redisProvider;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.mode = mode == null ? "local" : mode.trim().toLowerCase();
        this.maxRequests = Math.max(1, maxRequests);
        this.window = window.isNegative() || window.isZero() ? Duration.ofMinutes(1) : window;
        this.clientIps = clientIps;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !enabled
                || HttpMethod.OPTIONS.matches(request.getMethod())
                || !path.startsWith("/api/")
                || "/api/health".equals(path);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        RateLimitDecision decision = allow(request);
        if (!decision.allowed()) {
            writeRateLimitError(response, decision);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private RateLimitDecision allow(HttpServletRequest request) {
        String key = "omnivid:security:api-rate:" + clientIps.resolve(request) + ":" + bucket();
        if ("redis".equals(mode)) {
            StringRedisTemplate redis = redisProvider.getIfAvailable();
            if (redis != null) {
                Long count = redis.opsForValue().increment(key);
                if (count != null && count == 1L) {
                    redis.expire(key, window.plusSeconds(2));
                }
                return new RateLimitDecision(count == null || count <= maxRequests, count == null ? 0 : count.intValue());
            }
        }

        WindowCounter counter = localCounters.compute(key, (ignored, current) -> {
            long currentBucket = bucket();
            if (current == null || current.bucket() != currentBucket) {
                return new WindowCounter(currentBucket, new AtomicInteger(0));
            }
            return current;
        });
        int count = counter.count().incrementAndGet();
        return new RateLimitDecision(count <= maxRequests, count);
    }

    private long bucket() {
        return Instant.now().getEpochSecond() / window.toSeconds();
    }

    private void writeRateLimitError(HttpServletResponse response, RateLimitDecision decision) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), Map.of(
                "timestamp", Instant.now().toString(),
                "message", "API rate limit exceeded",
                "detail", "requests=%d; maxRequests=%d; windowSeconds=%d".formatted(
                        decision.requests(),
                        maxRequests,
                        window.toSeconds()
                ),
                "traceId", TraceContext.currentTraceId() == null ? "" : TraceContext.currentTraceId()
        ));
    }

    private record RateLimitDecision(boolean allowed, int requests) {
    }

    private record WindowCounter(long bucket, AtomicInteger count) {
    }
}
