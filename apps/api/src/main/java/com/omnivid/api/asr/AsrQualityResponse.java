package com.omnivid.api.asr;

public record AsrQualityResponse(
        boolean garbledRisk,
        int replacementCount,
        int controlCount,
        int suspiciousLatinCount,
        int traditionalCount,
        int cjkCount,
        String sample
) {
}
