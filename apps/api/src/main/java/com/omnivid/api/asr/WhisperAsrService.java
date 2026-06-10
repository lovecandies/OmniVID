package com.omnivid.api.asr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnivid.api.media.AudioExtractionResult;
import com.omnivid.api.storage.StoredVideoFile;
import com.omnivid.api.transcript.SubtitleTextSanitizer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
    private static final String TECHNICAL_HOTWORD_PROMPT = """
            Technical video transcript. Preserve English technical terms:
            Java backend, MySQL, Redis, Redisson, SETNX, MyBatis, Spring Boot, Spring Cloud,
            JVM, JDK, GC, OOM, CAS, AQS, MQ, RocketMQ, RabbitMQ, Docker, Qdrant,
            Embedding, Rerank, Vector, RAG, Agent, DeepSeek, Qwen, ChatGPT, Claude Code, Codex.
            Use Simplified Chinese for Chinese speech. Do not output Traditional Chinese or mojibake.
            """.replaceAll("\\s+", " ").trim();

    private final String asrPath;
    private final String modelPath;
    private final String resolvedModelPath;
    private final String language;
    private final String initialPrompt;
    private final int beamSize;
    private final int bestOf;
    private final int maxLen;
    private final Duration timeout;
    private final ObjectMapper objectMapper;
    private final SubtitleTextSanitizer sanitizer;

    public WhisperAsrService(
            @Value("${omnivid.asr.path}") String asrPath,
            @Value("${omnivid.asr.model}") String modelPath,
            @Value("${omnivid.asr.language:auto}") String language,
            @Value("${omnivid.asr.initial-prompt:}") String initialPrompt,
            @Value("${omnivid.asr.beam-size:5}") int beamSize,
            @Value("${omnivid.asr.best-of:5}") int bestOf,
            @Value("${omnivid.asr.max-len:72}") int maxLen,
            @Value("${omnivid.asr.timeout}") Duration timeout,
            ObjectMapper objectMapper,
            SubtitleTextSanitizer sanitizer
    ) {
        this.asrPath = asrPath;
        this.modelPath = modelPath;
        this.resolvedModelPath = resolveBestModel(modelPath);
        this.language = language;
        this.initialPrompt = initialPrompt;
        this.beamSize = beamSize;
        this.bestOf = bestOf;
        this.maxLen = maxLen;
        this.timeout = timeout;
        this.objectMapper = objectMapper;
        this.sanitizer = sanitizer;
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
        List<String> command = new ArrayList<>();
        command.add(asrPath);
        command.add("-m");
        command.add(resolvedModelPath);
        command.add("-f");
        command.add(audioPath.toString());
        command.add("-l");
        command.add(blankToDefault(language, "auto"));
        command.add("-bs");
        command.add(String.valueOf(Math.max(1, beamSize)));
        command.add("-bo");
        command.add(String.valueOf(Math.max(1, bestOf)));
        command.add("-ml");
        command.add(String.valueOf(Math.max(0, maxLen)));
        command.add("-sow");
        command.add("--suppress-nst");
        String prompt = effectivePrompt();
        if (!prompt.isBlank()) {
            command.add("--prompt");
            command.add(prompt);
        }
        command.add("-oj");
        command.add("-of");
        command.add(outputPrefix.toString());
        return command;
    }

    private AsrTranscriptionResult parse(Path outputJson) throws IOException {
        JsonNode root = objectMapper.readTree(Files.readString(outputJson, StandardCharsets.UTF_8));
        String language = root.path("result").path("language").asText("unknown");
        List<AsrTranscriptSegment> segments = new ArrayList<>();

        for (JsonNode node : root.path("transcription")) {
            String text = sanitizer.normalize(node.path("text").asText(""));
            if (text.isBlank()) {
                continue;
            }
            long startMs = node.path("offsets").path("from").asLong(0);
            long endMs = node.path("offsets").path("to").asLong(startMs + 1);
            segments.add(new AsrTranscriptSegment(startMs, endMs, text));
        }

        return new AsrTranscriptionResult(language, segments);
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String effectivePrompt() {
        String configured = blankToDefault(initialPrompt, "");
        if (configured.isBlank()) {
            return TECHNICAL_HOTWORD_PROMPT;
        }
        String lower = configured.toLowerCase();
        if (lower.contains("mybatis") && lower.contains("qdrant") && lower.contains("rocketmq")) {
            return configured;
        }
        return configured + " " + TECHNICAL_HOTWORD_PROMPT;
    }

    private String resolveBestModel(String configuredModelPath) {
        Path configured = Path.of(configuredModelPath).toAbsolutePath().normalize();
        Path modelDir = configured.getParent();
        if (modelDir == null) {
            return configured.toString();
        }
        for (String candidate : List.of(
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

    private String fileNameFromLocalPath(String localPath) {
        int slash = localPath.lastIndexOf('/');
        return slash >= 0 ? localPath.substring(slash + 1) : localPath;
    }
}
