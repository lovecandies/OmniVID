package com.omnivid.api.transcript;

public record TranscriptSnapshotSegment(
        int segmentIndex,
        long startMs,
        long endMs,
        String speaker,
        String content,
        int tokenCount
) {
    public TranscriptDraft toDraft() {
        return new TranscriptDraft(segmentIndex, startMs, endMs, speaker, content, tokenCount);
    }
}
