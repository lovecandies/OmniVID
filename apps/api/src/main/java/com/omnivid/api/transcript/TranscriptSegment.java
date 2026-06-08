package com.omnivid.api.transcript;

public record TranscriptSegment(
        long id,
        long videoId,
        int segmentIndex,
        long startMs,
        long endMs,
        String speaker,
        String content,
        int tokenCount
) {
}
