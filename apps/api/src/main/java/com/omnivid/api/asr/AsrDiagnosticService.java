package com.omnivid.api.asr;

import com.omnivid.api.common.ApiException;
import com.omnivid.api.job.ProcessingJob;
import com.omnivid.api.job.ProcessingJobRepository;
import com.omnivid.api.storage.LocalVideoStorageService;
import com.omnivid.api.transcript.TranscriptRepository;
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
    private final String asrPath;
    private final String modelPath;

    public AsrDiagnosticService(
            VideoRepository videos,
            ProcessingJobRepository jobs,
            TranscriptRepository transcripts,
            LocalVideoStorageService storage,
            @Value("${omnivid.asr.path}") String asrPath,
            @Value("${omnivid.asr.model}") String modelPath
    ) {
        this.videos = videos;
        this.jobs = jobs;
        this.transcripts = transcripts;
        this.storage = storage;
        this.asrPath = asrPath;
        this.modelPath = modelPath;
    }

    public AsrDiagnosticResponse inspect(long videoId) {
        VideoAsset video = videos.findById(videoId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Video not found"));
        ProcessingJob job = jobs.findLatestByVideoId(videoId).orElse(null);

        Path videoPath = storage.resolveLocalFile(video.storagePath());
        Path videoDir = videoPath.getParent();
        Path audioPath = videoDir.resolve("audio.wav");
        Path asrJsonPath = videoDir.resolve("asr.json");
        Path asrLogPath = videoDir.resolve("asr.log");
        Path ffmpegLogPath = videoDir.resolve("ffmpeg.log");

        return new AsrDiagnosticResponse(
                video.id(),
                video.originalName(),
                video.status(),
                asrPath,
                modelPath,
                Files.isRegularFile(Path.of(modelPath)),
                Files.isRegularFile(audioPath),
                sizeOrZero(audioPath),
                Files.isRegularFile(asrJsonPath),
                sizeOrZero(asrJsonPath),
                Files.isRegularFile(asrLogPath),
                sizeOrZero(asrLogPath),
                transcripts.countByVideoId(videoId),
                job == null ? "-" : job.currentStep(),
                job == null ? "-" : job.status(),
                job == null ? "" : job.errorMessage(),
                readTail(ffmpegLogPath),
                readTail(asrLogPath)
        );
    }

    private long sizeOrZero(Path path) {
        try {
            return Files.isRegularFile(path) ? Files.size(path) : 0;
        } catch (IOException exception) {
            return 0;
        }
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
}
