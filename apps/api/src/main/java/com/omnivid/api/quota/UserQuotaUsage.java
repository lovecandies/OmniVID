package com.omnivid.api.quota;

public record UserQuotaUsage(
        long storageBytes,
        int videoCount,
        int knowledgeBaseCount
) {
}
