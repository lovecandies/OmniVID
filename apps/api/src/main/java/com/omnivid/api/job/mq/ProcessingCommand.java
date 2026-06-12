package com.omnivid.api.job.mq;

import com.omnivid.api.observability.TraceContext;

public record ProcessingCommand(
        long videoId,
        long jobId,
        boolean replaceExistingTranscripts,
        String traceId
) {
    public ProcessingCommand {
        if (traceId == null || traceId.isBlank()) {
            traceId = TraceContext.currentOrNew();
        }
    }

    public ProcessingCommand(long videoId, long jobId, boolean replaceExistingTranscripts) {
        this(videoId, jobId, replaceExistingTranscripts, TraceContext.currentOrNew());
    }
}
