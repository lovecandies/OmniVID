package com.omnivid.api.media;

import java.util.List;

public record AudioExtractionResult(
        String audioPath,
        long sizeBytes,
        String transcriptionAudioPath,
        long transcriptionSizeBytes,
        boolean vadApplied,
        String vadMapPath,
        List<AudioVadSegment> vadSegments
) {
    public AudioExtractionResult(String audioPath, long sizeBytes) {
        this(audioPath, sizeBytes, audioPath, sizeBytes, false, "", List.of());
    }
}
