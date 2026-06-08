package com.omnivid.api.transcript;

public record TranscriptDraft(
        int segmentIndex,
        long startMs,
        long endMs,
        String speaker,
        String content,
        int tokenCount
) {
}
