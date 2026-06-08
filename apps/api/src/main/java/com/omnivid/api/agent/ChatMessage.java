package com.omnivid.api.agent;

import java.time.Instant;

public record ChatMessage(
        long id,
        long videoId,
        String role,
        String content,
        String citation,
        Instant createdAt
) {
}
