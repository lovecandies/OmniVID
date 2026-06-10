package com.omnivid.api.asr;

public record AsrDiagnosticResponse(
        long videoId,
        String originalName,
        String videoStatus,
        String asrPath,
        String modelPath,
        String audioFilter,
        String language,
        int beamSize,
        int bestOf,
        int maxLen,
        String promptPreview,
        boolean modelExists,
        boolean audioExists,
        long audioSizeBytes,
        boolean asrJsonExists,
        long asrJsonSizeBytes,
        boolean asrLogExists,
        long asrLogSizeBytes,
        int transcriptCount,
        AsrQualityResponse quality,
        String lastJobStep,
        String lastJobStatus,
        String lastJobError,
        String ffmpegLogTail,
        String asrLogTail
) {
}
