package com.omnivid.api.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnivid.api.common.ApiException;
import com.omnivid.api.llm.CloudLlmClient;
import com.omnivid.api.llm.CloudLlmResult;
import com.omnivid.api.summary.SummaryAsset;
import com.omnivid.api.summary.SummaryRepository;
import com.omnivid.api.transcript.TranscriptRepository;
import com.omnivid.api.transcript.TranscriptSegment;
import com.omnivid.api.video.VideoAsset;
import com.omnivid.api.video.VideoRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ExportDocumentGenerator {
    private static final int MAX_TRANSCRIPT_CHARS = 14_000;
    private static final Set<String> SUPPORTED_TYPES = Set.of(
            "CORE_POINTS", "MEETING_MINUTES", "BLOG_OUTLINE", "PPT_OUTLINE"
    );

    private final VideoRepository videos;
    private final TranscriptRepository transcripts;
    private final SummaryRepository summaries;
    private final CloudLlmClient llm;
    private final ObjectMapper objectMapper;
    private final Map<String, DocumentGeneration> cache = new ConcurrentHashMap<>();

    public ExportDocumentGenerator(
            VideoRepository videos,
            TranscriptRepository transcripts,
            SummaryRepository summaries,
            CloudLlmClient llm,
            ObjectMapper objectMapper
    ) {
        this.videos = videos;
        this.transcripts = transcripts;
        this.summaries = summaries;
        this.llm = llm;
        this.objectMapper = objectMapper;
    }

    public DocumentGeneration generate(long videoId, String requestedType) {
        String summaryType = requestedType == null ? "" : requestedType.trim().toUpperCase();
        if (!SUPPORTED_TYPES.contains(summaryType)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Unsupported summary type: " + requestedType);
        }
        VideoAsset video = videos.findById(videoId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Video not found"));
        List<TranscriptSegment> segments = transcripts.listByVideoId(videoId);
        SummaryAsset summary = summaries.listByVideoId(videoId).stream()
                .filter(asset -> summaryType.equals(asset.type()))
                .findFirst()
                .orElse(null);
        List<String> outline = summaryItems(summary);
        if (segments.isEmpty() && outline.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "Video has no transcript or summary to export");
        }
        String cacheKey = cacheKey(videoId, summaryType, outline, segments);
        DocumentGeneration cached = cache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        Optional<CloudLlmResult> generated = llm.complete(
                systemPrompt(summaryType),
                userPrompt(video, summaryType, outline, segments),
                3_600
        );
        if (generated.isPresent()) {
            Optional<ExportDocument> document = parseDocument(generated.get().content());
            if (document.isPresent()) {
                return cache(cacheKey, new DocumentGeneration(document.get(), generated.get().model(), "deepseek"));
            }
        }
        return cache(cacheKey, new DocumentGeneration(
                localDocument(video, summaryType, outline, segments),
                "local-template",
                "local-fallback"
        ));
    }

    private String systemPrompt(String summaryType) {
        return """
                你是 OmniVid 的企业级文档撰写助手。请基于视频字幕和已有大纲，生成一份详细、专业、可以直接交付的%s。
                只能使用提供的字幕事实；事实不足时明确写“视频未明确说明”，不能编造人名、日期、数字或结论。
                必须返回严格 JSON，不要 Markdown，不要代码块。
                JSON 固定结构：
                {
                  "title":"文档标题",
                  "subtitle":"一句话副标题",
                  "executiveSummary":"200-400字执行摘要",
                  "sections":[{"heading":"章节标题","bullets":["详细要点1","详细要点2"]}],
                  "actionItems":["行动项或后续建议"],
                  "sourceNotes":["[00:00-00:30] 可追溯证据摘要"]
                }
                sections 至少 5 个章节，每章 3-6 条详细要点；actionItems 至少 3 条；sourceNotes 至少 3 条并保留时间戳。
                会议纪要必须包含会议目标、议题讨论、关键决策、行动项和未决问题。
                博客必须包含引言、核心论述、案例或证据、实践建议和结论。
                PPT 内容必须按演示叙事组织，每个章节可以直接作为一页幻灯片。
                核心观点必须包含结论、依据、影响和建议。
                """.formatted(typeLabel(summaryType));
    }

    private String userPrompt(VideoAsset video, String summaryType, List<String> outline, List<TranscriptSegment> segments) {
        return """
                视频标题：%s
                目标文档：%s

                已有大纲：
                %s

                带时间戳字幕：
                %s
                """.formatted(
                video.originalName(),
                typeLabel(summaryType),
                outline.isEmpty() ? "无，请直接从字幕整理" : String.join("\n", outline),
                compactTranscript(segments)
        );
    }

    private Optional<ExportDocument> parseDocument(String content) {
        try {
            JsonNode root = objectMapper.readTree(stripJsonFence(content));
            List<ExportSection> sections = new ArrayList<>();
            for (JsonNode section : root.path("sections")) {
                String heading = section.path("heading").asText("").trim();
                List<String> bullets = readArray(section, "bullets");
                if (!heading.isBlank() && !bullets.isEmpty()) {
                    sections.add(new ExportSection(heading, bullets));
                }
            }
            String title = root.path("title").asText("").trim();
            String executiveSummary = root.path("executiveSummary").asText("").trim();
            if (title.isBlank() || executiveSummary.isBlank() || sections.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new ExportDocument(
                    title,
                    root.path("subtitle").asText("").trim(),
                    executiveSummary,
                    sections,
                    readArray(root, "actionItems"),
                    readArray(root, "sourceNotes")
            ));
        } catch (Exception exception) {
            return Optional.empty();
        }
    }

    private ExportDocument localDocument(
            VideoAsset video,
            String summaryType,
            List<String> outline,
            List<TranscriptSegment> segments
    ) {
        List<String> evidence = sourceNotes(segments);
        List<String> source = outline.isEmpty()
                ? segments.stream().limit(8).map(TranscriptSegment::content).toList()
                : outline;
        Map<String, List<String>> sections = new LinkedHashMap<>();
        sections.put("目标与背景", List.of(
                "本文件根据视频《" + video.originalName() + "》的时间轴字幕自动整理。",
                "导出目标为" + typeLabel(summaryType) + "，重点保留可追溯事实和后续行动。",
                "云端模型不可用时，本地生成器不会扩写字幕外事实。"
        ));
        int index = 1;
        for (String item : source.stream().limit(6).toList()) {
            sections.put("关键内容 " + index++, List.of(
                    item,
                    "该内容来自当前视频已有字幕或结构化大纲。",
                    "建议结合来源时间戳复核语境后再用于正式决策。"
            ));
        }
        sections.put("结论与建议", List.of(
                "优先复核高价值片段，并将确认后的结论沉淀到知识库。",
                "对视频未明确说明的责任人、截止时间和量化指标，需要会后补充确认。",
                "字幕人工修订后可重新导出，以获得更准确的交付文件。"
        ));
        return new ExportDocument(
                video.originalName() + " - " + typeLabel(summaryType),
                "OmniVid 基于时间轴字幕生成",
                executiveSummary(source),
                sections.entrySet().stream().map(entry -> new ExportSection(entry.getKey(), entry.getValue())).toList(),
                List.of(
                        "复核引用片段与关键结论是否一致。",
                        "补充视频未明确说明的责任人和截止时间。",
                        "将确认后的结论同步到团队知识库。"
                ),
                evidence
        );
    }

    private List<String> summaryItems(SummaryAsset summary) {
        if (summary == null || summary.contentJson() == null || summary.contentJson().isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(summary.contentJson());
            for (String key : List.of("points", "items", "hooks")) {
                List<String> values = readArray(root, key);
                if (!values.isEmpty()) {
                    return values;
                }
            }
        } catch (Exception ignored) {
            return List.of(summary.contentJson());
        }
        return List.of();
    }

    private List<String> sourceNotes(List<TranscriptSegment> segments) {
        return segments.stream()
                .filter(segment -> !segment.content().isBlank())
                .limit(10)
                .map(segment -> "[%s-%s] %s".formatted(
                        formatTime(segment.startMs()),
                        formatTime(segment.endMs()),
                        compact(segment.content(), 180)
                ))
                .toList();
    }

    private String compactTranscript(List<TranscriptSegment> segments) {
        StringBuilder builder = new StringBuilder();
        for (TranscriptSegment segment : segments) {
            String line = "[%s-%s] %s".formatted(
                    formatTime(segment.startMs()),
                    formatTime(segment.endMs()),
                    segment.content().trim()
            );
            if (builder.length() + line.length() + 1 > MAX_TRANSCRIPT_CHARS) {
                break;
            }
            if (!segment.content().isBlank()) {
                builder.append(line).append('\n');
            }
        }
        return builder.toString().trim();
    }

    private String executiveSummary(List<String> source) {
        String joined = source.isEmpty()
                ? "视频已完成解析，但当前可用文字信息有限。"
                : String.join("；", source.stream().limit(5).toList());
        return compact("本文件围绕视频中的主要观点、关键证据和后续行动进行结构化整理。核心内容包括：" + joined
                + "。正式使用前建议结合时间戳来源复核，并补充视频未明确说明的信息。", 480);
    }

    private List<String> readArray(JsonNode root, String field) {
        List<String> values = new ArrayList<>();
        JsonNode node = root.path(field);
        if (!node.isArray()) {
            return values;
        }
        for (JsonNode item : node) {
            String value = item.asText("").trim();
            if (!value.isBlank()) {
                values.add(value);
            }
        }
        return values;
    }

    private String typeLabel(String type) {
        return switch (type) {
            case "MEETING_MINUTES" -> "详细会议纪要";
            case "BLOG_OUTLINE" -> "结构化长文博客";
            case "PPT_OUTLINE" -> "演示汇报稿";
            default -> "核心观点报告";
        };
    }

    private String stripJsonFence(String content) {
        String text = content.trim();
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline) {
                return text.substring(firstNewline + 1, lastFence).trim();
            }
        }
        return text;
    }

    private String compact(String value, int limit) {
        String text = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return text.length() <= limit ? text : text.substring(0, limit) + "...";
    }

    private String formatTime(long ms) {
        long seconds = ms / 1000;
        return "%02d:%02d".formatted(seconds / 60, seconds % 60);
    }

    private String cacheKey(long videoId, String summaryType, List<String> outline, List<TranscriptSegment> segments) {
        int sourceHash = 31 * outline.hashCode() + segments.stream()
                .map(segment -> segment.startMs() + ":" + segment.endMs() + ":" + segment.content())
                .toList()
                .hashCode();
        return videoId + ":" + summaryType + ":" + sourceHash + ":" + llm.configVersion();
    }

    private DocumentGeneration cache(String key, DocumentGeneration generation) {
        if (cache.size() >= 100) {
            cache.clear();
        }
        cache.put(key, generation);
        return generation;
    }

    public record DocumentGeneration(ExportDocument document, String model, String mode) {
    }
}
