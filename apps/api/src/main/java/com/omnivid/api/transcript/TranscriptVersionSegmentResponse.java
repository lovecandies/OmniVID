package com.omnivid.api.transcript;

public record TranscriptVersionSegmentResponse(
        int segmentIndex,
        long startMs,
        long endMs,
        String speaker,
        String content,
        String currentContent,
        boolean changed
) {
}
