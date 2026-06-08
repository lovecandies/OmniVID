package com.omnivid.api.ratelimit;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "omnivid.agent-rate-limit", name = "mode", havingValue = "local", matchIfMissing = true)
public class LocalAgentRateLimiter implements AgentRateLimiter {
    private static final int LIMIT = 5;
    private static final long WINDOW_SECONDS = 10;

    private final ConcurrentHashMap<String, WindowCounter> counters = new ConcurrentHashMap<>();

    @Override
    public boolean allow(String scope) {
        long window = Instant.now().getEpochSecond() / WINDOW_SECONDS;
        WindowCounter counter = counters.compute(scope, (key, current) -> {
            if (current == null || current.window() != window) {
                return new WindowCounter(window, new AtomicInteger(0));
            }
            return current;
        });
        return counter.count().incrementAndGet() <= LIMIT;
    }

    private record WindowCounter(long window, AtomicInteger count) {
    }
}
