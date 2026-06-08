package com.omnivid.api.asr;

import java.util.List;

public record AsrTranscriptionResult(
        String language,
        List<AsrTranscriptSegment> segments
) {
}
