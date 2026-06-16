package com.omnivid.api.media;

public record AudioVadSegment(
        long sourceStartMs,
        long sourceEndMs,
        long transcriptionStartMs,
        long transcriptionEndMs
) {
}
