package com.omnivid.api.asr;

public record OcrSubtitleSampleResponse(
        int segmentIndex,
        long startMs,
        long endMs,
        String asrText,
        String ocrText,
        String fusedText,
        double confidence,
        boolean ocrAvailable,
        boolean replacementSuggested,
        double cer,
        double similarity
) {
}
