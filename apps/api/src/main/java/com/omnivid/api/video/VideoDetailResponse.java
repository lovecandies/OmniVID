package com.omnivid.api.video;

import com.omnivid.api.job.ProcessingJob;
import com.omnivid.api.summary.SummaryAsset;
import com.omnivid.api.transcript.TranscriptSegment;
import java.util.List;

public record VideoDetailResponse(
        VideoAsset video,
        ProcessingJob job,
        List<TranscriptSegment> transcripts,
        List<SummaryAsset> summaries
) {
}
