package com.omnivid.api.job;

import java.time.Instant;

public record FailedJobResponse(
        long jobId,
        long videoId,
        String originalName,
        String currentStep,
        int progress,
        int retryCount,
        String errorMessage,
        Instant updatedAt
) {
}
