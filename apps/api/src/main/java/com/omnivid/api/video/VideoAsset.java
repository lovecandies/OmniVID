package com.omnivid.api.video;

import java.time.Instant;

public record VideoAsset(
        long id,
        long userId,
        String md5,
        String originalName,
        String storagePath,
        long fileSizeBytes,
        long durationMs,
        String status,
        Instant createdAt,
        Instant updatedAt,
        int version
) {
}
