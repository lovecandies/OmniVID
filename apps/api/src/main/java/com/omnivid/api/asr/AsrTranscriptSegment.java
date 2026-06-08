package com.omnivid.api.asr;

public record AsrTranscriptSegment(
        long startMs,
        long endMs,
        String text
) {
}
