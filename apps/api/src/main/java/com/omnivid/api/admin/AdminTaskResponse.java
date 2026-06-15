package com.omnivid.api.admin;

import java.time.Instant;

public record AdminTaskResponse(
        long jobId,
        long videoId,
        long userId,
        String userEmail,
        String originalName,
        String currentStep,
        String status,
        int progress,
        int retryCount,
        String errorMessage,
        Instant updatedAt
) {
}
