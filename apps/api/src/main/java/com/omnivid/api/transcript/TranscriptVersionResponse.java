package com.omnivid.api.transcript;

import java.time.Instant;

public record TranscriptVersionResponse(
        long id,
        long videoId,
        int versionNo,
        String source,
        String note,
        int segmentCount,
        String preview,
        Instant createdAt
) {
}
