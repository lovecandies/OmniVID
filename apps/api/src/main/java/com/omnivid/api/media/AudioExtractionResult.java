package com.omnivid.api.media;

public record AudioExtractionResult(
        String audioPath,
        long sizeBytes
) {
}
