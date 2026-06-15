package com.omnivid.api.admin;

public record AdminResourceUsage(
        int userCount,
        int activeUserCount,
        int videoCount,
        long storageBytes,
        int knowledgeBaseCount,
        int failedJobCount,
        int runningJobCount
) {
}
