package com.omnivid.api.asr;

import com.omnivid.api.video.CompleteUploadResponse;
import com.omnivid.api.video.VideoService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/videos/{videoId}/asr")
public class AsrDiagnosticController {
    private final AsrDiagnosticService diagnostics;
    private final VideoService videos;

    public AsrDiagnosticController(AsrDiagnosticService diagnostics, VideoService videos) {
        this.diagnostics = diagnostics;
        this.videos = videos;
    }

    @GetMapping("/diagnostics")
    AsrDiagnosticResponse inspect(@PathVariable long videoId) {
        return diagnostics.inspect(videoId);
    }

    @PostMapping("/repair-encoding")
    TranscriptRepairResponse repairEncoding(@PathVariable long videoId) {
        return videos.repairTranscriptEncoding(videoId);
    }

    @PostMapping("/reprocess")
    CompleteUploadResponse reprocess(@PathVariable long videoId) {
        return videos.reprocessAsr(videoId);
    }

    @GetMapping("/evaluate-ocr")
    OcrSubtitleQualityResponse evaluateOcr(@PathVariable long videoId) {
        return videos.evaluateBurnedSubtitleOcr(videoId);
    }

    @PostMapping("/fuse-ocr")
    OcrSubtitleQualityResponse fuseOcr(@PathVariable long videoId) {
        return videos.fuseBurnedSubtitleOcr(videoId);
    }

    @PostMapping("/align-ocr")
    OcrSubtitleQualityResponse alignOcr(@PathVariable long videoId) {
        return videos.alignBurnedSubtitleOcr(videoId);
    }

    @PostMapping("/refine-low-confidence")
    OcrSubtitleQualityResponse refineLowConfidence(@PathVariable long videoId) {
        return videos.refineLowConfidenceSubtitles(videoId);
    }
}
