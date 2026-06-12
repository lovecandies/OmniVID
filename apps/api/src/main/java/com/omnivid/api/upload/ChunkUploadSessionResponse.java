package com.omnivid.api.upload;

import com.omnivid.api.video.CompleteUploadResponse;
import java.util.List;

public record ChunkUploadSessionResponse(
        String sessionId,
        String fileName,
        long fileSize,
        String fileMd5,
        long partSize,
        int totalParts,
        long uploadedBytes,
        String status,
        List<Integer> uploadedParts,
        List<Integer> missingParts,
        boolean deduplicated,
        CompleteUploadResponse upload
) {
}
