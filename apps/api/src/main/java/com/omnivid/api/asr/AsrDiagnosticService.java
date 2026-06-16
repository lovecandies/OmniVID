package com.omnivid.api.asr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnivid.api.common.ApiException;
import com.omnivid.api.job.ProcessingJob;
import com.omnivid.api.job.ProcessingJobRepository;
import com.omnivid.api.storage.LocalVideoStorageService;
import com.omnivid.api.transcript.TranscriptRepository;
import com.omnivid.api.transcript.TranscriptSegment;
import com.omnivid.api.transcript.SubtitleTextSanitizer;
import com.omnivid.api.video.VideoAsset;
import com.omnivid.api.video.VideoRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class AsrDiagnosticService {
    private final VideoRepository videos;
    private final ProcessingJobRepository jobs;
    private final TranscriptRepository transcripts;
    private final LocalVideoStorageService storage;
    private final SubtitleTextSanitizer sanitizer;
    private final ObjectMapper objectMapper;
    private final String asrPath;
    private final String modelPath;
    private final String audioFilter;
    private final String resolvedModelPath;
    private final String language;
    private final String initialPrompt;
    private final int beamSize;
    private final int bestOf;
    private final int maxLen;
    private final boolean ocrAutoFusionEnabled;
    private final String ocrAutoFusionMode;

    public AsrDiagnosticService(
            VideoRepository videos,
            ProcessingJobRepository jobs,
            TranscriptRepository transcripts,
            LocalVideoStorageService storage,
            SubtitleTextSanitizer sanitizer,
            ObjectMapper objectMapper,
            @Value("${omnivid.asr.path}") String asrPath,
            @Value("${omnivid.asr.model}") String modelPath,
            @Value("${omnivid.ffmpeg.audio-filter:}") String audioFilter,
            @Value("${omnivid.asr.language:auto}") String language,
            @Value("${omnivid.asr.initial-prompt:}") String initialPrompt,
            @Value("${omnivid.asr.beam-size:5}") int beamSize,
            @Value("${omnivid.asr.best-of:5}") int bestOf,
            @Value("${omnivid.asr.max-len:72}") int maxLen,
            @Value("${omnivid.ocr.auto-fusion-enabled:true}") boolean ocrAutoFusionEnabled,
            @Value("${omnivid.ocr.auto-fusion-mode:conservative}") String ocrAutoFusionMode
    ) {
        this.videos = videos;
        this.jobs = jobs;
        this.transcripts = transcripts;
        this.storage = storage;
        this.sanitizer = sanitizer;
        this.objectMapper = objectMapper;
        this.asrPath = asrPath;
        this.modelPath = modelPath;
        this.audioFilter = audioFilter == null ? "" : audioFilter.trim();
        this.resolvedModelPath = resolveBestModel(modelPath);
        this.language = language;
        this.initialPrompt = initialPrompt;
        this.beamSize = beamSize;
        this.bestOf = bestOf;
        this.maxLen = maxLen;
        this.ocrAutoFusionEnabled = ocrAutoFusionEnabled;
        this.ocrAutoFusionMode = ocrAutoFusionMode;
    }

    public AsrDiagnosticResponse inspect(long videoId) {
        VideoAsset video = videos.findById(videoId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Video not found"));
        ProcessingJob job = jobs.findLatestByVideoId(videoId).orElse(null);

        Path videoPath = storage.resolveLocalFile(video.storagePath());
        Path videoDir = videoPath.getParent();
        Path audioPath = videoDir.resolve("audio.wav");
        Path transcriptionAudioPath = videoDir.resolve("audio-vad.wav");
        Path vadMapPath = videoDir.resolve("audio-vad-map.json");
        Path asrJsonPath = videoDir.resolve("asr.json");
        Path asrLogPath = videoDir.resolve("asr.log");
        Path ffmpegLogPath = videoDir.resolve("ffmpeg.log");

        boolean vadMapExists = Files.isRegularFile(vadMapPath);
        return new AsrDiagnosticResponse(
                video.id(),
                video.originalName(),
                video.status(),
                asrPath,
                resolvedModelPath,
                audioFilter,
                language,
                beamSize,
                bestOf,
                maxLen,
                promptPreview(),
                Files.isRegularFile(Path.of(resolvedModelPath)),
                Files.isRegularFile(audioPath),
                sizeOrZero(audioPath),
                Files.isRegularFile(transcriptionAudioPath),
                sizeOrZero(transcriptionAudioPath),
                vadMapExists,
                vadMapExists,
                vadMapExists ? "local://" + vadMapPath.getFileName() : "",
                sizeOrZero(vadMapPath),
                countVadSegments(vadMapPath),
                Files.isRegularFile(asrJsonPath),
                sizeOrZero(asrJsonPath),
                Files.isRegularFile(asrLogPath),
                sizeOrZero(asrLogPath),
                transcripts.countByVideoId(videoId),
                inspectQuality(videoId),
                job == null ? "-" : job.currentStep(),
                job == null ? "-" : job.status(),
                job == null ? "" : job.errorMessage(),
                readTail(ffmpegLogPath),
                readTail(asrLogPath),
                ocrAutoFusionEnabled,
                ocrAutoFusionMode
        );
    }

    private AsrQualityResponse inspectQuality(long videoId) {
        StringBuilder builder = new StringBuilder();
        for (TranscriptSegment segment : transcripts.listByVideoId(videoId)) {
            if (segment.content().isBlank()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(segment.content());
            if (builder.length() >= 600) {
                break;
            }
        }
        String sample = sanitizer.normalize(builder.toString());
        SubtitleTextSanitizer.QualityReport report = sanitizer.inspect(sample);
        return new AsrQualityResponse(
                report.garbledRisk(),
                report.replacementCount(),
                report.controlCount(),
                report.suspiciousLatinCount(),
                report.traditionalCount(),
                report.cjkCount(),
                sample.length() <= 160 ? sample : sample.substring(0, 160)
        );
    }

    private int countVadSegments(Path path) {
        try {
            if (!Files.isRegularFile(path)) {
                return 0;
            }
            JsonNode segments = objectMapper.readTree(path.toFile()).path("segments");
            return segments.isArray() ? segments.size() : 0;
        } catch (IOException exception) {
            return 0;
        }
    }

    private long sizeOrZero(Path path) {
        try {
            return Files.isRegularFile(path) ? Files.size(path) : 0;
        } catch (IOException exception) {
            return 0;
        }
    }

    private String promptPreview() {
        String text = initialPrompt == null ? "" : initialPrompt.replaceAll("\\s+", " ").trim();
        if (text.isBlank()) {
            return "technical hotword fallback";
        }
        return text.length() <= 180 ? text : text.substring(0, 180) + "...";
    }

    private String readTail(Path path) {
        try {
            if (!Files.isRegularFile(path)) {
                return "";
            }
            String text = Files.readString(path, StandardCharsets.UTF_8).replaceAll("\\s+", " ").trim();
            return text.length() <= 600 ? text : text.substring(text.length() - 600);
        } catch (IOException exception) {
            return "";
        }
    }

    private String resolveBestModel(String configuredModelPath) {
        Path configured = Path.of(configuredModelPath).toAbsolutePath().normalize();
        Path modelDir = configured.getParent();
        if (modelDir == null) {
            return configured.toString();
        }
        for (String candidate : java.util.List.of(
                "ggml-medium.bin",
                "ggml-small.bin",
                "ggml-base.bin",
                "ggml-tiny.bin",
                configured.getFileName().toString()
        )) {
            Path path = modelDir.resolve(candidate).toAbsolutePath().normalize();
            if (Files.isRegularFile(path)) {
                return path.toString();
            }
        }
        return configured.toString();
    }
}
