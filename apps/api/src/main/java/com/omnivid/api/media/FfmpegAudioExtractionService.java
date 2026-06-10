package com.omnivid.api.media;

import com.omnivid.api.storage.StoredVideoFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class FfmpegAudioExtractionService {
    private final String ffmpegPath;
    private final String audioFilter;
    private final Duration timeout;

    public FfmpegAudioExtractionService(
            @Value("${omnivid.ffmpeg.path}") String ffmpegPath,
            @Value("${omnivid.ffmpeg.audio-filter:}") String audioFilter,
            @Value("${omnivid.ffmpeg.timeout}") Duration timeout
    ) {
        this.ffmpegPath = ffmpegPath;
        this.audioFilter = audioFilter == null ? "" : audioFilter.trim();
        this.timeout = timeout;
    }

    public AudioExtractionResult extractToWav(StoredVideoFile videoFile) {
        Path inputPath = videoFile.localPath().toAbsolutePath().normalize();
        Path outputPath = inputPath.getParent().resolve("audio.wav").toAbsolutePath().normalize();
        Path logPath = inputPath.getParent().resolve("ffmpeg.log").toAbsolutePath().normalize();

        try {
            Files.deleteIfExists(outputPath);
            runFfmpeg(inputPath, outputPath, logPath, !audioFilter.isBlank());

            long sizeBytes = Files.size(outputPath);
            if (sizeBytes == 0) {
                throw new AudioExtractionException("ffmpeg produced an empty audio file");
            }

            return new AudioExtractionResult(toLocalPath(outputPath), sizeBytes);
        } catch (IOException exception) {
            throw new AudioExtractionException("ffmpeg execution failed", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AudioExtractionException("ffmpeg execution was interrupted", exception);
        }
    }

    private void runFfmpeg(Path inputPath, Path outputPath, Path logPath, boolean enhanced) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command(inputPath, outputPath, enhanced))
                .directory(Path.of(ffmpegPath).toAbsolutePath().getParent().toFile())
                .redirectErrorStream(true)
                .redirectOutput(logPath.toFile())
                .start();

        boolean completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!completed) {
            process.destroyForcibly();
            throw new AudioExtractionException("ffmpeg timed out after " + timeout.toSeconds() + "s");
        }

        if (process.exitValue() != 0) {
            if (enhanced) {
                Files.deleteIfExists(outputPath);
                runFfmpeg(inputPath, outputPath, logPath, false);
                return;
            }
            throw new AudioExtractionException("ffmpeg exited with code " + process.exitValue());
        }
    }

    private List<String> command(Path inputPath, Path outputPath, boolean enhanced) {
        java.util.ArrayList<String> command = new java.util.ArrayList<>(List.of(
                ffmpegPath,
                "-y",
                "-i",
                inputPath.toString(),
                "-vn"
        ));
        if (enhanced) {
            command.add("-af");
            command.add(audioFilter);
        }
        command.addAll(List.of(
                "-ac",
                "1",
                "-ar",
                "16000",
                "-acodec",
                "pcm_s16le",
                outputPath.toString()
        ));
        return command;
    }

    private String toLocalPath(Path path) {
        return "local://" + path.getFileName();
    }
}
