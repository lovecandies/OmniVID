package com.omnivid.api.asr;

import java.util.List;

public record OcrSubtitleQualityResponse(
        long videoId,
        String mode,
        boolean ocrAvailable,
        String message,
        int sampledCount,
        int ocrHitCount,
        int replacementCount,
        int appliedReplacementCount,
        double averageCer,
        double averageSimilarity,
        double averageFusedCer,
        double averageFusedSimilarity,
        List<OcrSubtitleSampleResponse> samples
) {
}
