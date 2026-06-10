package com.omnivid.api.asr;

public record TranscriptRepairResponse(
        long videoId,
        int scanned,
        int repaired,
        boolean vectorReindexed,
        String message
) {
}
