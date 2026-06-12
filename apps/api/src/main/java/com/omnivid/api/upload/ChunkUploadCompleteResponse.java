package com.omnivid.api.upload;

import com.omnivid.api.video.CompleteUploadResponse;

public record ChunkUploadCompleteResponse(
        ChunkUploadSessionResponse session,
        CompleteUploadResponse upload
) {
}
