package com.omnivid.api.quota;

public record UserQuotaResponse(
        long userId,
        long storageBytes,
        long maxStorageBytes,
        int videoCount,
        int maxVideoCount,
        int knowledgeBaseCount,
        int maxKnowledgeBaseCount
) {
    public static UserQuotaResponse of(UserQuota quota, UserQuotaUsage usage) {
        return new UserQuotaResponse(
                quota.userId(),
                usage.storageBytes(),
                quota.maxStorageBytes(),
                usage.videoCount(),
                quota.maxVideoCount(),
                usage.knowledgeBaseCount(),
                quota.maxKnowledgeBaseCount()
        );
    }
}
