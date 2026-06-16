package com.omnivid.api.media;

import com.omnivid.api.storage.StoredVideoFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class VideoMetadataProbeService {
    private final String ffprobePath;
    private final Duration timeout;

    public VideoMetadataProbeService(
            @Value("${omnivid.ffmpeg.path}") String ffmpegPath,
            @Value("${omnivid.ffmpeg.timeout}") Duration timeout
    ) {
        Path ffmpeg = Path.of(ffmpegPath);
        String ffprobeName = ffmpeg.getFileName() != null
                && ffmpeg.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".exe")
                ? "ffprobe.exe"
                : "ffprobe";
        this.ffprobePath = ffmpeg.getParent() == null
                ? ffprobeName
                : ffmpeg.getParent().resolve(ffprobeName).toString();
        this.timeout = timeout;
    }

    public long durationMs(StoredVideoFile videoFile) {
        Path inputPath = videoFile.localPath().toAbsolutePath().normalize();
        Path logPath = inputPath.getParent().resolve("ffprobe.log").toAbsolutePath().normalize();
        try {
            Process process = new ProcessBuilder(command(inputPath))
                    .redirectErrorStream(true)
                    .redirectOutput(logPath.toFile())
                    .start();
            boolean completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!completed) {
                process.destroyForcibly();
                return 0;
            }
            if (process.exitValue() != 0 || Files.notExists(logPath)) {
                return 0;
            }
            return parseDurationMs(Files.readString(logPath));
        } catch (IOException exception) {
            return 0;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return 0;
        }
    }

    private List<String> command(Path inputPath) {
        return List.of(
                ffprobePath,
                "-v",
                "error",
                "-show_entries",
                "format=duration",
                "-of",
                "default=noprint_wrappers=1:nokey=1",
                inputPath.toString()
        );
    }

    private long parseDurationMs(String output) {
        try {
            double seconds = Double.parseDouble(output.trim());
            return Math.max(0, Math.round(seconds * 1000));
        } catch (NumberFormatException exception) {
            return 0;
        }
    }
}
