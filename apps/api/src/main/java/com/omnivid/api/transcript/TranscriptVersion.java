package com.omnivid.api.transcript;

import java.time.Instant;

public record TranscriptVersion(
        long id,
        long videoId,
        int versionNo,
        String source,
        String note,
        String snapshotJson,
        Instant createdAt
) {
}
