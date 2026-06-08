package com.omnivid.api.ratelimit;

public interface AgentRateLimiter {
    boolean allow(String scope);
}
