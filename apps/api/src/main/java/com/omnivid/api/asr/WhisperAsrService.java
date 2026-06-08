package com.omnivid.api.asr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnivid.api.media.AudioExtractionResult;
import com.omnivid.api.storage.StoredVideoFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class WhisperAsrService {
    private final String asrPath;
    private final String modelPath;
    private final Duration timeout;
    private final ObjectMapper objectMapper;

    public WhisperAsrService(
            @Value("${omnivid.asr.path}") String asrPath,
            @Value("${omnivid.asr.model}") String modelPath,
            @Value("${omnivid.asr.timeout}") Duration timeout,
            ObjectMapper objectMapper
    ) {
        this.asrPath = asrPath;
        this.modelPath = modelPath;
        this.timeout = timeout;
        this.objectMapper = objectMapper;
    }

    public AsrTranscriptionResult transcribe(StoredVideoFile videoFile, AudioExtractionResult audioResult) {
        Path videoPath = videoFile.localPath().toAbsolutePath().normalize();
        Path audioPath = videoPath.getParent().resolve(fileNameFromLocalPath(audioResult.audioPath())).normalize();
        Path outputPrefix = videoPath.getParent().resolve("asr").toAbsolutePath().normalize();
        Path outputJson = Path.of(outputPrefix.toString() + ".json");
        Path logPath = videoPath.getParent().resolve("asr.log").toAbsolutePath().normalize();

        try {
            Files.deleteIfExists(outputJson);
            Process process = new ProcessBuilder(command(audioPath, outputPrefix))
                    .directory(Path.of(asrPath).toAbsolutePath().getParent().toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(logPath.toFile())
                    .start();

            boolean completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!completed) {
                process.destroyForcibly();
                throw new AsrTranscriptionException("ASR timed out after " + timeout.toSeconds() + "s");
            }

            if (process.exitValue() != 0) {
                throw new AsrTranscriptionException("ASR exited with code " + process.exitValue());
            }
            if (Files.notExists(outputJson)) {
                throw new AsrTranscriptionException("ASR did not produce JSON output");
            }

            return parse(outputJson);
        } catch (IOException exception) {
            throw new AsrTranscriptionException("ASR execution failed", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AsrTranscriptionException("ASR execution was interrupted", exception);
        }
    }

    private List<String> command(Path audioPath, Path outputPrefix) {
        return List.of(
                asrPath,
                "-m",
                modelPath,
                "-f",
                audioPath.toString(),
                "-l",
                "auto",
                "-oj",
                "-of",
                outputPrefix.toString()
        );
    }

    private AsrTranscriptionResult parse(Path outputJson) throws IOException {
        JsonNode root = objectMapper.readTree(outputJson.toFile());
        String language = root.path("result").path("language").asText("unknown");
        List<AsrTranscriptSegment> segments = new ArrayList<>();

        for (JsonNode node : root.path("transcription")) {
            String text = node.path("text").asText("").trim();
            if (text.isBlank()) {
                continue;
            }
            long startMs = node.path("offsets").path("from").asLong(0);
            long endMs = node.path("offsets").path("to").asLong(startMs + 1);
            segments.add(new AsrTranscriptSegment(startMs, endMs, text));
        }

        return new AsrTranscriptionResult(language, segments);
    }

    private String fileNameFromLocalPath(String localPath) {
        int slash = localPath.lastIndexOf('/');
        return slash >= 0 ? localPath.substring(slash + 1) : localPath;
    }
}
