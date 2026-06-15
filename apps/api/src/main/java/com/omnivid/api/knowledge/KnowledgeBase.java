package com.omnivid.api.knowledge;

import java.time.Instant;

public record KnowledgeBase(
        long id,
        long userId,
        String name,
        String description,
        int videoCount,
        Instant createdAt,
        Instant updatedAt
) {
}
