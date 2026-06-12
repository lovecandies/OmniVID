package com.omnivid.api.upload;

import java.util.List;

public record ChunkUploadPartResponse(
        String sessionId,
        int partNumber,
        String partMd5,
        long sizeBytes,
        long uploadedBytes,
        String status,
        List<Integer> uploadedParts,
        List<Integer> missingParts
) {
}
