package com.omnivid.api.video;

import com.omnivid.api.job.ProcessingJob;

public record CompleteUploadResponse(
        boolean deduplicated,
        VideoAsset video,
        ProcessingJob job
) {
}
