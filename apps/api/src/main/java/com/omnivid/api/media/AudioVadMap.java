package com.omnivid.api.media;

import java.util.List;

public record AudioVadMap(
        String sourceAudioPath,
        String transcriptionAudioPath,
        long sourceDurationMs,
        long transcriptionDurationMs,
        long sourceSizeBytes,
        long transcriptionSizeBytes,
        List<AudioVadSegment> segments
) {
}