package com.omnivid.api.progress;

public record ProgressSnapshot(
        long videoId,
        long jobId,
        String currentStep,
        String status,
        int progress
) {
}
