package com.omnivid.api.quota;

public record UserQuota(
        long userId,
        long maxStorageBytes,
        int maxVideoCount,
        int maxKnowledgeBaseCount
) {
}
