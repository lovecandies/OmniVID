package com.omnivid.api.upload;

public record ChunkUploadSession(
        String id,
        long userId,
        String fileName,
        long fileSize,
        String fileMd5,
        long partSize,
        int totalParts,
        long uploadedBytes,
        String status
) {
}
