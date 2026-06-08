package com.omnivid.api.job;

import java.time.Instant;

public record ProcessingJob(
        long id,
        long videoId,
        String currentStep,
        String status,
        int progress,
        int retryCount,
        String errorMessage,
        Instant startedAt,
        Instant finishedAt,
        Instant createdAt,
        Instant updatedAt,
        int version
) {
}
