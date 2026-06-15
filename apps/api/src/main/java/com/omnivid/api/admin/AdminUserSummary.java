package com.omnivid.api.admin;

import java.time.Instant;

public record AdminUserSummary(
        long id,
        String email,
        String nickname,
        boolean emailVerified,
        boolean disabled,
        Instant deletedAt,
        int videoCount,
        int maxVideoCount,
        long storageBytes,
        long maxStorageBytes,
        int knowledgeBaseCount,
        int maxKnowledgeBaseCount,
        Instant createdAt
) {
}
