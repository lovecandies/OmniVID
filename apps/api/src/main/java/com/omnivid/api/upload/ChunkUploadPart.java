package com.omnivid.api.upload;

public record ChunkUploadPart(
        String sessionId,
        int partNumber,
        long sizeBytes,
        String partMd5,
        String storagePath
) {
}
