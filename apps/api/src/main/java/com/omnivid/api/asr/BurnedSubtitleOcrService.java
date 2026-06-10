package com.omnivid.api.asr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnivid.api.common.ApiException;
import com.omnivid.api.storage.LocalVideoStorageService;
import com.omnivid.api.transcript.SubtitleTextSanitizer;
import com.omnivid.api.transcript.TranscriptSegment;
import com.omnivid.api.video.VideoAsset;
import com.omnivid.api.video.VideoRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class BurnedSubtitleOcrService {
    private final VideoRepository videos;
    private final LocalVideoStorageService storage;
    private final ObjectMapper objectMapper;
    private final SubtitleTextSanitizer sanitizer;
    private final String pythonPath;
    private final String scriptPath;
    private final String ffmpegPath;
    private final double minConfidence;
    private final Duration timeout;

    public BurnedSubtitleOcrService(
            VideoRepository videos,
            LocalVideoStorageService storage,
            ObjectMapper objectMapper,
            SubtitleTextSanitizer sanitizer,
            @Value("${omnivid.ocr.python-path:python}") String pythonPath,
            @Value("${omnivid.ocr.script-path:E:/video/scripts/ocr_burned_subtitles.py}") String scriptPath,
            @Value("${omnivid.ffmpeg.path}") String ffmpegPath,
            @Value("${omnivid.ocr.min-confidence:0.88}") double minConfidence,
            @Value("${omnivid.ocr.timeout:300s}") Duration timeout
    ) {
        this.videos = videos;
        this.storage = storage;
        this.objectMapper = objectMapper;
        this.sanitizer = sanitizer;
        this.pythonPath = pythonPath;
        this.scriptPath = scriptPath;
        this.ffmpegPath = ffmpegPath;
        this.minConfidence = minConfidence;
        this.timeout = timeout;
    }

    public OcrSubtitleQualityResponse evaluate(long videoId, List<TranscriptSegment> segments, int sampleLimit) {
        VideoAsset video = requireVideo(videoId);
        List<TranscriptSegment> sampled = sampleSegments(segments, sampleLimit);
        if (sampled.isEmpty()) {
            return empty(videoId, "evaluate", "No transcript segments available for OCR evaluation");
        }

        OcrRunResult run = runOcr(video, sampled);
        return buildResponse(videoId, "evaluate", run, sampled, List.of(), FusionMode.CONSERVATIVE);
    }

    public FusionResult fuse(long videoId, List<TranscriptSegment> segments, int sampleLimit) {
        return fuseWithMode(videoId, segments, sampleLimit, "fuse", FusionMode.CONSERVATIVE);
    }

    public FusionResult align(long videoId, List<TranscriptSegment> segments, int sampleLimit) {
        return fuseWithMode(videoId, segments, sampleLimit, "align-ocr", FusionMode.STRONG_VISUAL);
    }

    public FusionResult refineLowConfidence(long videoId, List<TranscriptSegment> segments, int sampleLimit) {
        return fuseWithMode(videoId, segments, sampleLimit, "refine-low-confidence", FusionMode.LOW_CONFIDENCE);
    }

    private FusionResult fuseWithMode(
            long videoId,
            List<TranscriptSegment> segments,
            int sampleLimit,
            String modeName,
            FusionMode mode
    ) {
        VideoAsset video = requireVideo(videoId);
        List<TranscriptSegment> sampled = sampleSegments(segments, sampleLimit);
        if (sampled.isEmpty()) {
            return new FusionResult(empty(videoId, modeName, "No transcript segments available for OCR fusion"), List.of());
        }

        OcrRunResult run = runOcr(video, sampled);
        List<FusedSegment> replacements = new ArrayList<>();
        for (OcrSample sample : run.samples()) {
            String asrText = sanitizer.repairIfBetter(sample.asrText()).text();
            String ocrText = sanitizer.repairIfBetter(sample.ocrText()).text();
            String fusedText = fuseText(asrText, ocrText, sample.confidence(), mode);
            if (!fusedText.isBlank() && !fusedText.equals(asrText)) {
                replacements.add(new FusedSegment(sample.segmentIndex(), fusedText));
            }
        }

        return new FusionResult(buildResponse(videoId, modeName, run, sampled, replacements, mode), replacements);
    }

    public OcrSubtitleQualityResponse withAppliedReplacementCount(
            OcrSubtitleQualityResponse response,
            int appliedReplacementCount
    ) {
        return new OcrSubtitleQualityResponse(
                response.videoId(),
                response.mode(),
                response.ocrAvailable(),
                response.message(),
                response.sampledCount(),
                response.ocrHitCount(),
                response.replacementCount(),
                appliedReplacementCount,
                response.averageCer(),
                response.averageSimilarity(),
                response.averageFusedCer(),
                response.averageFusedSimilarity(),
                response.samples()
        );
    }

    private VideoAsset requireVideo(long videoId) {
        return videos.findById(videoId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Video not found"));
    }

    private OcrSubtitleQualityResponse empty(long videoId, String mode, String message) {
        return new OcrSubtitleQualityResponse(videoId, mode, false, message, 0, 0, 0, 0, 1.0, 0.0, 1.0, 0.0, List.of());
    }

    private OcrRunResult runOcr(VideoAsset video, List<TranscriptSegment> sampled) {
        Path samplesJson = null;
        Path outputJson = null;
        try {
            samplesJson = Files.createTempFile("omnivid-ocr-samples-", ".json");
            outputJson = Files.createTempFile("omnivid-ocr-output-", ".json");
            List<Map<String, Object>> payload = sampled.stream()
                    .map(segment -> Map.<String, Object>of(
                            "segmentIndex", segment.segmentIndex(),
                            "startMs", segment.startMs(),
                            "endMs", segment.endMs(),
                            "asrText", segment.content()
                    ))
                    .toList();
            Files.writeString(samplesJson, objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8);

            List<String> command = List.of(
                    pythonPath,
                    scriptPath,
                    "--video",
                    storage.resolveLocalFile(video.storagePath()).toString(),
                    "--ffmpeg",
                    ffmpegPath,
                    "--samples",
                    samplesJson.toString(),
                    "--output",
                    outputJson.toString(),
                    "--min-score",
                    String.valueOf(minConfidence)
            );
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            boolean completed = process.waitFor(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            String log = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!completed) {
                process.destroyForcibly();
                return new OcrRunResult(false, "OCR timed out after " + timeout.toSeconds() + "s", List.of());
            }
            if (process.exitValue() != 0) {
                return new OcrRunResult(false, "OCR exited with code " + process.exitValue() + ": " + compact(log, 300), List.of());
            }
            if (Files.notExists(outputJson)) {
                return new OcrRunResult(false, "OCR did not produce output JSON", List.of());
            }
            return parseOcrOutput(outputJson);
        } catch (IOException exception) {
            return new OcrRunResult(false, "OCR execution failed: " + exception.getMessage(), List.of());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new OcrRunResult(false, "OCR execution interrupted", List.of());
        } finally {
            deleteQuietly(samplesJson);
            deleteQuietly(outputJson);
        }
    }

    private OcrRunResult parseOcrOutput(Path outputJson) throws IOException {
        JsonNode root = objectMapper.readTree(Files.readString(outputJson, StandardCharsets.UTF_8));
        boolean available = root.path("available").asBoolean(false);
        String error = root.path("error").asText("");
        List<OcrSample> samples = new ArrayList<>();
        for (JsonNode node : root.path("samples")) {
            samples.add(new OcrSample(
                    node.path("segmentIndex").asInt(),
                    node.path("startMs").asLong(),
                    node.path("endMs").asLong(),
                    sanitizer.repairIfBetter(node.path("asrText").asText("")).text(),
                    sanitizer.repairIfBetter(node.path("ocrText").asText("")).text(),
                    node.path("confidence").asDouble(0),
                    node.path("available").asBoolean(false)
            ));
        }
        return new OcrRunResult(available, error, samples);
    }

    private OcrSubtitleQualityResponse buildResponse(
            long videoId,
            String mode,
            OcrRunResult run,
            List<TranscriptSegment> sampled,
            List<FusedSegment> replacements,
            FusionMode fusionMode
    ) {
        Map<Integer, String> replacementMap = replacements.stream()
                .collect(java.util.stream.Collectors.toMap(FusedSegment::segmentIndex, FusedSegment::text));
        List<OcrSubtitleSampleResponse> responses = new ArrayList<>();
        double totalCer = 0;
        double totalSimilarity = 0;
        double totalFusedCer = 0;
        double totalFusedSimilarity = 0;
        int hits = 0;
        for (OcrSample sample : run.samples()) {
            String asrText = sanitizer.repairIfBetter(sample.asrText()).text();
            String ocrText = sanitizer.repairIfBetter(sample.ocrText()).text();
            String fusedText = replacementMap.getOrDefault(
                    sample.segmentIndex(),
                    fuseText(asrText, ocrText, sample.confidence(), fusionMode)
            );
            double cer = sample.available() ? characterErrorRate(asrText, ocrText) : 1.0;
            double similarity = sample.available() ? Math.max(0, 1 - cer) : 0.0;
            double fusedCer = sample.available() ? characterErrorRate(fusedText, ocrText) : 1.0;
            double fusedSimilarity = sample.available() ? Math.max(0, 1 - fusedCer) : 0.0;
            if (sample.available()) {
                totalCer += cer;
                totalSimilarity += similarity;
                totalFusedCer += fusedCer;
                totalFusedSimilarity += fusedSimilarity;
                hits++;
            }
            responses.add(new OcrSubtitleSampleResponse(
                    sample.segmentIndex(),
                    sample.startMs(),
                    sample.endMs(),
                    asrText,
                    ocrText,
                    fusedText,
                    round(sample.confidence()),
                    sample.available(),
                    !fusedText.equals(asrText),
                    round(cer),
                    round(similarity)
            ));
        }

        int replacementCount = (int) responses.stream().filter(OcrSubtitleSampleResponse::replacementSuggested).count();
        String message = run.available()
                ? "OCR evaluation completed; high-confidence burned-in subtitles can be used as visual references"
                : run.error();
        return new OcrSubtitleQualityResponse(
                videoId,
                mode,
                run.available(),
                message,
                sampled.size(),
                hits,
                replacementCount,
                0,
                hits == 0 ? 1.0 : round(totalCer / hits),
                hits == 0 ? 0.0 : round(totalSimilarity / hits),
                hits == 0 ? 1.0 : round(totalFusedCer / hits),
                hits == 0 ? 0.0 : round(totalFusedSimilarity / hits),
                responses
        );
    }

    private List<TranscriptSegment> sampleSegments(List<TranscriptSegment> segments, int sampleLimit) {
        List<TranscriptSegment> usable = segments.stream()
                .filter(segment -> !segment.content().isBlank())
                .sorted(Comparator.comparingLong(TranscriptSegment::startMs))
                .toList();
        if (usable.size() <= sampleLimit) {
            return usable;
        }

        List<TranscriptSegment> sampled = new ArrayList<>();
        double step = (double) usable.size() / sampleLimit;
        for (int index = 0; index < sampleLimit; index++) {
            sampled.add(usable.get(Math.min(usable.size() - 1, (int) Math.floor(index * step))));
        }
        return sampled;
    }

    private String fuseText(String asrText, String ocrText, double confidence, FusionMode mode) {
        String asr = normalizeTechTokens(sanitizer.repairIfBetter(asrText).text());
        String ocr = normalizeTechTokens(sanitizer.repairIfBetter(ocrText).text());
        if (ocr.isBlank() || confidence < minConfidence) {
            return asr;
        }
        if (mode == FusionMode.CONSERVATIVE && shouldReplaceWholeLine(asr, ocr, confidence)) {
            return ocr;
        }
        if (mode == FusionMode.STRONG_VISUAL && shouldUseStrongVisualLine(asr, ocr, confidence)) {
            return ocr;
        }
        if (mode == FusionMode.LOW_CONFIDENCE && shouldUseLowConfidenceVisualLine(asr, ocr, confidence)) {
            return ocr;
        }

        String fused = mergeTechnicalTokens(asr, ocr);
        if (!fused.equals(asr) && characterErrorRate(fused, ocr) < characterErrorRate(asr, ocr)) {
            return fused;
        }
        return asr;
    }

    private boolean shouldReplaceWholeLine(String asr, String ocr, double confidence) {
        if (confidence < 0.985 || ocr.length() < 8) {
            return false;
        }
        if (asr.isBlank()) {
            return true;
        }
        double lengthRatio = (double) Math.min(asr.length(), ocr.length()) / Math.max(asr.length(), ocr.length());
        double cer = characterErrorRate(asr, ocr);
        boolean completeVisualLine = ocr.length() >= 0.72 * asr.length() || ocr.length() >= 12;
        boolean visualLineIsClean = sanitizer.inspect(ocr).garbledRisk() == false;
        return visualLineIsClean && completeVisualLine && lengthRatio >= 0.62 && cer >= 0.16 && cer <= 0.72;
    }

    private boolean shouldUseStrongVisualLine(String asr, String ocr, double confidence) {
        if (confidence < 0.94 || !looksUsefulSubtitle(ocr) || hasGarbledRisk(ocr)) {
            return false;
        }
        if (asr.isBlank() || hasGarbledRisk(asr)) {
            return true;
        }
        double cer = characterErrorRate(asr, ocr);
        double lengthRatio = (double) Math.min(asr.length(), ocr.length()) / Math.max(asr.length(), ocr.length());
        boolean completeVisualLine = ocr.length() >= 0.55 * asr.length() || ocr.length() >= 10;
        return completeVisualLine && lengthRatio >= 0.45 && cer >= 0.08 && cer <= 0.88;
    }

    private boolean shouldUseLowConfidenceVisualLine(String asr, String ocr, double confidence) {
        if (confidence < 0.96 || !looksUsefulSubtitle(ocr) || hasGarbledRisk(ocr)) {
            return false;
        }
        if (asr.isBlank() || hasGarbledRisk(asr)) {
            return true;
        }
        double cer = characterErrorRate(asr, ocr);
        double lengthRatio = (double) Math.min(asr.length(), ocr.length()) / Math.max(asr.length(), ocr.length());
        boolean likelyAsrMistake = cer >= 0.28 || sanitizer.inspect(asr).traditionalCount() > 0;
        boolean visualLineCloseEnough = lengthRatio >= 0.42 && cer <= 0.92;
        return likelyAsrMistake && visualLineCloseEnough;
    }

    private boolean looksUsefulSubtitle(String text) {
        int useful = 0;
        int numeric = 0;
        for (int offset = 0; offset < text.length(); ) {
            int codePoint = text.codePointAt(offset);
            if (Character.isLetter(codePoint) || Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN) {
                useful++;
            }
            if (Character.isDigit(codePoint)) {
                numeric++;
            }
            offset += Character.charCount(codePoint);
        }
        return useful >= 2 && numeric * 2 < Math.max(1, text.length());
    }

    private boolean hasGarbledRisk(String text) {
        return sanitizer.inspect(text).garbledRisk();
    }

    private String mergeTechnicalTokens(String asr, String ocr) {
        String result = asr;
        String compactOcr = ocr.replace(" ", "");
        for (String token : List.of(
                "ClaudeCode", "Claude", "Codex", "ChatGPT", "DeepSeek", "Qwen",
                "MySQL", "Redis", "Redisson", "SETNX", "MyBatis", "SpringBoot", "SpringCloud",
                "JVM", "JDK", "GC", "OOM", "CAS", "AQS", "MQ", "RocketMQ", "RabbitMQ",
                "Docker", "Qdrant", "Embedding", "Rerank", "Vector", "Agent", "RAG",
                "API", "ASR", "OCR", "LLM", "Skill"
        )) {
            if (!compactOcr.toLowerCase(Locale.ROOT).contains(token.toLowerCase(Locale.ROOT))) {
                continue;
            }
            result = replaceConfusableToken(result, token);
        }
        return result;
    }

    private String normalizeTechTokens(String text) {
        return text.replaceAll("(?i)Claude\\s*Code", "Claude Code")
                .replaceAll("(?i)Spring\\s*Boot", "Spring Boot")
                .replaceAll("(?i)Spring\\s*Cloud", "Spring Cloud")
                .replaceAll("(?i)Rocket\\s*MQ", "RocketMQ")
                .replaceAll("(?i)Rabbit\\s*MQ", "RabbitMQ")
                .replaceAll("(?i)\\bskil\\b", "skill")
                .replaceAll("(?i)\\bskyo\\b", "skill");
    }

    private String replaceConfusableToken(String text, String token) {
        if ("ClaudeCode".equals(token)) {
            return text.replaceAll("(?i)Code\\s*code", "Claude Code")
                    .replaceAll("(?i)ClaudeCode", "Claude Code");
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains(token.toLowerCase(Locale.ROOT))) {
            return text;
        }
        return text;
    }

    private enum FusionMode {
        CONSERVATIVE,
        STRONG_VISUAL,
        LOW_CONFIDENCE
    }

    private double characterErrorRate(String hypothesis, String reference) {
        String left = normalizeForDistance(hypothesis);
        String right = normalizeForDistance(reference);
        if (right.isEmpty()) {
            return left.isEmpty() ? 0.0 : 1.0;
        }
        return Math.min(1.0, (double) levenshtein(left, right) / right.length());
    }

    private String normalizeForDistance(String text) {
        return sanitizer.repairIfBetter(text).text()
                .replaceAll("\\s+", "")
                .replace("，", ",")
                .replace("。", ".")
                .toLowerCase(Locale.ROOT);
    }

    private int levenshtein(String left, String right) {
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= left.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(
                        Math.min(current[j - 1] + 1, previous[j] + 1),
                        previous[j - 1] + cost
                );
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[right.length()];
    }

    private double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    private String compact(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Temporary OCR files are best-effort cleanup.
        }
    }

    public record FusionResult(OcrSubtitleQualityResponse quality, List<FusedSegment> replacements) {
    }

    public record FusedSegment(int segmentIndex, String text) {
    }

    private record OcrRunResult(boolean available, String error, List<OcrSample> samples) {
    }

    private record OcrSample(
            int segmentIndex,
            long startMs,
            long endMs,
            String asrText,
            String ocrText,
            double confidence,
            boolean available
    ) {
    }
}
