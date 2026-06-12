package com.omnivid.api.transcript;

import java.time.Instant;
import java.util.List;

public record TranscriptVersionDetailResponse(
        long id,
        long videoId,
        int versionNo,
        String source,
        String note,
        int segmentCount,
        int changedCount,
        Instant createdAt,
        List<TranscriptVersionSegmentResponse> segments
) {
}
