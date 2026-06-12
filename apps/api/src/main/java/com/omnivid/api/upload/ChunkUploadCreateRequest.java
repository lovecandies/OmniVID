package com.omnivid.api.upload;

public record ChunkUploadCreateRequest(
        String fileName,
        long fileSize,
        String fileMd5,
        long partSize,
        Integer totalParts
) {
}
