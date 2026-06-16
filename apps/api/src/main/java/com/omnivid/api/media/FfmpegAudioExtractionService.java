package com.omnivid.api.media;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnivid.api.storage.StoredVideoFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class FfmpegAudioExtractionService {
    private static final Pattern SILENCE_START_PATTERN = Pattern.compile("silence_start:\\s*([0-9]+(?:\\.[0-9]+)?)");
    private static final Pattern SILENCE_END_PATTERN = Pattern.compile("silence_end:\\s*([0-9]+(?:\\.[0-9]+)?)");
    private static final long MERGE_GAP_MS = 80;
    private static final long MIN_SPEECH_DURATION_MS = 200;
    private static final long MIN_GAIN_MS = 1_000;
    private static final double MIN_GAIN_RATIO = 0.08d;
    private static final double SILENCE_THRESHOLD_DB = -35.0d;
    private static final long SILENCE_MIN_DURATION_MS = 350;
    private static final long VAD_PADDING_MS = 120;

    private final String ffmpegPath;
    private final String audioFilter;
    private final Duration timeout;
    private final ObjectMapper objectMapper;
    private final VideoMetadataProbeService metadataProbe;

    public FfmpegAudioExtractionService(
            @Value("${omnivid.ffmpeg.path}") String ffmpegPath,
            @Value("${omnivid.ffmpeg.audio-filter:}") String audioFilter,
            @Value("${omnivid.ffmpeg.timeout}") Duration timeout,
            ObjectMapper objectMapper,
            VideoMetadataProbeService metadataProbe
    ) {
        this.ffmpegPath = ffmpegPath;
        this.audioFilter = audioFilter == null ? "" : audioFilter.trim();
        this.timeout = timeout;
        this.objectMapper = objectMapper;
        this.metadataProbe = metadataProbe;
    }

    public AudioExtractionResult extractToWav(StoredVideoFile videoFile) {
        Path inputPath = videoFile.localPath().toAbsolutePath().normalize();
        Path workDir = inputPath.getParent();
        Path rawAudioPath = workDir.resolve("audio-raw.wav").toAbsolutePath().normalize();
        Path audioPath = workDir.resolve("audio.wav").toAbsolutePath().normalize();
        Path vadAudioPath = workDir.resolve("audio-vad.wav").toAbsolutePath().normalize();
        Path vadMapPath = workDir.resolve("audio-vad-map.json").toAbsolutePath().normalize();
        Path extractLogPath = workDir.resolve("ffmpeg.log").toAbsolutePath().normalize();
        Path vadDetectLogPath = workDir.resolve("audio-vad-detect.log").toAbsolutePath().normalize();
        Path vadTrimLogPath = workDir.resolve("audio-vad-trim.log").toAbsolutePath().normalize();

        try {
            deleteIfExists(rawAudioPath, audioPath, vadAudioPath, vadMapPath, extractLogPath, vadDetectLogPath, vadTrimLogPath);
            runExtraction(inputPath, rawAudioPath, extractLogPath);

            long sourceSizeBytes = Files.size(rawAudioPath);
            if (sourceSizeBytes == 0) {
                throw new AudioExtractionException("ffmpeg produced an empty audio file");
            }

            long sourceDurationMs = metadataProbe.durationMs(new StoredVideoFile(
                    videoFile.originalName(),
                    videoFile.md5(),
                    videoFile.storagePath(),
                    rawAudioPath,
                    sourceSizeBytes
            ));
            if (sourceDurationMs <= 0) {
                sourceDurationMs = metadataProbe.durationMs(videoFile);
            }

            VadExtractionOutcome vadOutcome = buildVadAudio(
                    rawAudioPath,
                    audioPath,
                    vadAudioPath,
                    vadMapPath,
                    vadDetectLogPath,
                    vadTrimLogPath,
                    sourceDurationMs,
                    sourceSizeBytes
            );

            long audioSizeBytes = Files.size(audioPath);
            long transcriptionSizeBytes = Files.size(vadAudioPath);
            if (audioSizeBytes == 0 || transcriptionSizeBytes == 0) {
                throw new AudioExtractionException("ffmpeg produced an empty VAD audio file");
            }

            return new AudioExtractionResult(
                    toLocalPath(audioPath),
                    audioSizeBytes,
                    toLocalPath(vadAudioPath),
                    transcriptionSizeBytes,
                    vadOutcome.applied(),
                    vadOutcome.applied() ? toLocalPath(vadMapPath) : "",
                    vadOutcome.segments()
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AudioExtractionException("ffmpeg execution was interrupted", exception);
        } catch (IOException exception) {
            throw new AudioExtractionException("ffmpeg execution failed", exception);
        }
    }

    private void runExtraction(Path inputPath, Path outputPath, Path logPath) throws IOException, InterruptedException {
        try {
            runCommand(buildExtractionCommand(inputPath, outputPath, !audioFilter.isBlank()), logPath);
        } catch (AudioExtractionException exception) {
            if (audioFilter.isBlank()) {
                throw exception;
            }
            Files.deleteIfExists(outputPath);
            runCommand(buildExtractionCommand(inputPath, outputPath, false), logPath);
        }
    }

    private VadExtractionOutcome buildVadAudio(
            Path rawAudioPath,
            Path audioPath,
            Path vadAudioPath,
            Path vadMapPath,
            Path vadDetectLogPath,
            Path vadTrimLogPath,
            long sourceDurationMs,
            long sourceSizeBytes
    ) throws IOException, InterruptedException {
        if (sourceDurationMs <= 0) {
            copyFallback(rawAudioPath, audioPath, vadAudioPath, vadMapPath);
            return new VadExtractionOutcome(false, List.of());
        }

        List<SilenceInterval> silenceIntervals;
        List<AudioWindow> speechWindows;
        try {
            silenceIntervals = detectSilenceIntervals(rawAudioPath, vadDetectLogPath, sourceDurationMs);
            speechWindows = buildSpeechWindows(silenceIntervals, sourceDurationMs);
        } catch (IOException | InterruptedException | AudioExtractionException exception) {
            copyFallback(rawAudioPath, audioPath, vadAudioPath, vadMapPath);
            if (exception instanceof InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
            }
            return new VadExtractionOutcome(false, List.of());
        }

        long transcriptionDurationMs = speechWindows.stream().mapToLong(AudioWindow::durationMs).sum();
        long minimumGainMs = Math.max(MIN_GAIN_MS, Math.round(sourceDurationMs * MIN_GAIN_RATIO));
        if (speechWindows.isEmpty() || transcriptionDurationMs <= 0 || sourceDurationMs - transcriptionDurationMs < minimumGainMs) {
            copyFallback(rawAudioPath, audioPath, vadAudioPath, vadMapPath);
            return new VadExtractionOutcome(false, List.of());
        }

        try {
            runCommand(buildTrimCommand(rawAudioPath, audioPath, speechWindows), vadTrimLogPath);
            long audioSizeBytes = Files.size(audioPath);
            if (audioSizeBytes == 0) {
                copyFallback(rawAudioPath, audioPath, vadAudioPath, vadMapPath);
                return new VadExtractionOutcome(false, List.of());
            }
            Files.copy(audioPath, vadAudioPath, StandardCopyOption.REPLACE_EXISTING);
            long transcriptionSizeBytes = Files.size(vadAudioPath);
            List<AudioVadSegment> vadSegments = buildVadSegments(speechWindows);
            writeVadMap(
                    vadMapPath,
                    rawAudioPath,
                    vadAudioPath,
                    sourceDurationMs,
                    transcriptionDurationMs,
                    sourceSizeBytes,
                    transcriptionSizeBytes,
                    vadSegments
            );
            return new VadExtractionOutcome(true, vadSegments);
        } catch (IOException | InterruptedException | AudioExtractionException exception) {
            copyFallback(rawAudioPath, audioPath, vadAudioPath, vadMapPath);
            if (exception instanceof InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
            }
            return new VadExtractionOutcome(false, List.of());
        }
    }

    private List<SilenceInterval> detectSilenceIntervals(Path rawAudioPath, Path logPath, long sourceDurationMs) throws IOException, InterruptedException {
        runCommand(List.of(
                ffmpegPath,
                "-y",
                "-i",
                rawAudioPath.toString(),
                "-af",
                buildSilenceDetectFilter(),
                "-f",
                "null",
                "-"
        ), logPath);

        List<SilenceInterval> intervals = new ArrayList<>();
        Double silenceStart = null;
        for (String line : Files.readAllLines(logPath)) {
            Matcher startMatcher = SILENCE_START_PATTERN.matcher(line);
            while (startMatcher.find()) {
                silenceStart = toDouble(startMatcher.group(1));
            }
            Matcher endMatcher = SILENCE_END_PATTERN.matcher(line);
            while (endMatcher.find()) {
                if (silenceStart != null) {
                    double silenceEnd = toDouble(endMatcher.group(1));
                    intervals.add(new SilenceInterval(
                            Math.max(0L, Math.round(silenceStart * 1000.0d)),
                            Math.max(0L, Math.round(silenceEnd * 1000.0d))
                    ));
                    silenceStart = null;
                }
            }
        }
        if (silenceStart != null && sourceDurationMs > 0) {
            intervals.add(new SilenceInterval(
                    Math.max(0L, Math.round(silenceStart * 1000.0d)),
                    sourceDurationMs
            ));
        }
        return mergeSilenceIntervals(intervals);
    }

    private List<AudioWindow> buildSpeechWindows(List<SilenceInterval> silenceIntervals, long sourceDurationMs) {
        if (sourceDurationMs <= 0) {
            return List.of();
        }

        List<AudioWindow> windows = new ArrayList<>();
        long cursorMs = 0;
        for (SilenceInterval silenceInterval : silenceIntervals) {
            long speechStartMs = clamp(cursorMs, 0, sourceDurationMs);
            long speechEndMs = clamp(silenceInterval.startMs(), speechStartMs, sourceDurationMs);
            if (speechEndMs > speechStartMs) {
                windows.add(new AudioWindow(speechStartMs, speechEndMs));
            }
            cursorMs = Math.max(cursorMs, clamp(silenceInterval.endMs(), 0, sourceDurationMs));
        }

        if (cursorMs < sourceDurationMs) {
            windows.add(new AudioWindow(cursorMs, sourceDurationMs));
        }

        List<AudioWindow> padded = new ArrayList<>();
        for (AudioWindow window : windows) {
            long startMs = Math.max(0, window.startMs() - VAD_PADDING_MS);
            long endMs = Math.min(sourceDurationMs, window.endMs() + VAD_PADDING_MS);
            if (endMs - startMs < MIN_SPEECH_DURATION_MS) {
                continue;
            }
            if (!padded.isEmpty()) {
                AudioWindow previous = padded.get(padded.size() - 1);
                if (startMs <= previous.endMs() + MERGE_GAP_MS) {
                    padded.set(padded.size() - 1, new AudioWindow(previous.startMs(), Math.max(previous.endMs(), endMs)));
                    continue;
                }
            }
            padded.add(new AudioWindow(startMs, endMs));
        }
        return padded;
    }

    private List<SilenceInterval> mergeSilenceIntervals(List<SilenceInterval> intervals) {
        if (intervals.isEmpty()) {
            return List.of();
        }

        List<SilenceInterval> sorted = new ArrayList<>(intervals);
        sorted.sort(Comparator.comparingLong(SilenceInterval::startMs));
        List<SilenceInterval> merged = new ArrayList<>();
        SilenceInterval current = sorted.getFirst();
        for (int index = 1; index < sorted.size(); index++) {
            SilenceInterval next = sorted.get(index);
            if (next.startMs() <= current.endMs() + MERGE_GAP_MS) {
                current = new SilenceInterval(current.startMs(), Math.max(current.endMs(), next.endMs()));
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);
        return merged;
    }

    private List<AudioVadSegment> buildVadSegments(List<AudioWindow> speechWindows) {
        List<AudioVadSegment> segments = new ArrayList<>();
        long transcriptionCursorMs = 0;
        for (AudioWindow window : speechWindows) {
            long durationMs = window.durationMs();
            segments.add(new AudioVadSegment(
                    window.startMs(),
                    window.endMs(),
                    transcriptionCursorMs,
                    transcriptionCursorMs + durationMs
            ));
            transcriptionCursorMs += durationMs;
        }
        return segments;
    }

    private void writeVadMap(
            Path vadMapPath,
            Path sourceAudioPath,
            Path transcriptionAudioPath,
            long sourceDurationMs,
            long transcriptionDurationMs,
            long sourceSizeBytes,
            long transcriptionSizeBytes,
            List<AudioVadSegment> segments
    ) throws IOException {
        AudioVadMap map = new AudioVadMap(
                toLocalPath(sourceAudioPath),
                toLocalPath(transcriptionAudioPath),
                sourceDurationMs,
                transcriptionDurationMs,
                sourceSizeBytes,
                transcriptionSizeBytes,
                segments
        );
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(vadMapPath.toFile(), map);
    }

    private void copyFallback(Path rawAudioPath, Path audioPath, Path vadAudioPath, Path vadMapPath) throws IOException {
        Files.copy(rawAudioPath, audioPath, StandardCopyOption.REPLACE_EXISTING);
        Files.copy(rawAudioPath, vadAudioPath, StandardCopyOption.REPLACE_EXISTING);
        Files.deleteIfExists(vadMapPath);
    }

    private void deleteIfExists(Path... paths) throws IOException {
        for (Path path : paths) {
            Files.deleteIfExists(path);
        }
    }

    private void runCommand(List<String> command, Path logPath) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command)
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
            throw new AudioExtractionException("ffmpeg exited with code " + process.exitValue());
        }
    }

    private List<String> buildExtractionCommand(Path inputPath, Path outputPath, boolean enhanced) {
        List<String> command = new ArrayList<>();
        command.add(ffmpegPath);
        command.add("-y");
        command.add("-i");
        command.add(inputPath.toString());
        command.add("-vn");
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

    private List<String> buildTrimCommand(Path inputPath, Path outputPath, List<AudioWindow> speechWindows) {
        if (speechWindows.size() == 1) {
            AudioWindow window = speechWindows.getFirst();
            return List.of(
                    ffmpegPath,
                    "-y",
                    "-i",
                    inputPath.toString(),
                    "-vn",
                    "-af",
                    "atrim=start=" + formatSeconds(window.startMs()) + ":end=" + formatSeconds(window.endMs()) + ",asetpts=PTS-STARTPTS",
                    "-ac",
                    "1",
                    "-ar",
                    "16000",
                    "-acodec",
                    "pcm_s16le",
                    outputPath.toString()
            );
        }

        StringBuilder filter = new StringBuilder();
        for (int index = 0; index < speechWindows.size(); index++) {
            AudioWindow window = speechWindows.get(index);
            if (index > 0) {
                filter.append(';');
            }
            filter.append("[0:a]atrim=start=")
                    .append(formatSeconds(window.startMs()))
                    .append(":end=")
                    .append(formatSeconds(window.endMs()))
                    .append(",asetpts=PTS-STARTPTS[a")
                    .append(index)
                    .append(']');
        }
        filter.append(';');
        for (int index = 0; index < speechWindows.size(); index++) {
            filter.append("[a").append(index).append(']');
        }
        filter.append("concat=n=").append(speechWindows.size()).append(":v=0:a=1[outa]");

        return List.of(
                ffmpegPath,
                "-y",
                "-i",
                inputPath.toString(),
                "-vn",
                "-filter_complex",
                filter.toString(),
                "-map",
                "[outa]",
                "-ac",
                "1",
                "-ar",
                "16000",
                "-acodec",
                "pcm_s16le",
                outputPath.toString()
        );
    }

    private String buildSilenceDetectFilter() {
        return "silencedetect=n=" + formatDb(SILENCE_THRESHOLD_DB) + ":d=" + formatSeconds(SILENCE_MIN_DURATION_MS);
    }

    private String formatDb(double value) {
        return String.format(Locale.ROOT, "%.1fdB", value);
    }

    private String formatSeconds(long milliseconds) {
        return String.format(Locale.ROOT, "%.3f", milliseconds / 1000.0d);
    }

    private double toDouble(String value) {
        return Double.parseDouble(value.trim());
    }

    private long clamp(long value, long minimum, long maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private String toLocalPath(Path path) {
        return "local://" + path.getFileName();
    }

    private record SilenceInterval(long startMs, long endMs) {
    }

    private record AudioWindow(long startMs, long endMs) {
        long durationMs() {
            return Math.max(0, endMs - startMs);
        }
    }

    private record VadExtractionOutcome(boolean applied, List<AudioVadSegment> segments) {
    }
}
