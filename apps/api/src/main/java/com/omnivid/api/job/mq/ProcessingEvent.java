package com.omnivid.api.job.mq;

import java.time.Instant;

public record ProcessingEvent(
        String eventId,
        long jobId,
        long videoId,
        String eventType,
        String payloadJson,
        String status,
        int attemptCount,
        String lastError,
        Instant nextAttemptAt,
        Instant createdAt,
        Instant updatedAt,
        Instant consumedAt
) {
}
