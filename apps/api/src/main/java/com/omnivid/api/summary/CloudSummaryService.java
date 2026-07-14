package com.omnivid.api.summary;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnivid.api.llm.CloudLlmClient;
import com.omnivid.api.llm.CloudLlmResult;
import com.omnivid.api.llm.LlmProviderService;
import com.omnivid.api.video.VideoAsset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class CloudSummaryService {
    private static final int MAX_TRANSCRIPT_CHARS = 8_000;

    private final CloudLlmClient llm;
    private final ObjectMapper objectMapper;
    private final LlmProviderService llmProviders;

    public CloudSummaryService(CloudLlmClient llm, ObjectMapper objectMapper, LlmProviderService llmProviders) {
        this.llm = llm;
        this.objectMapper = objectMapper;
        this.llmProviders = llmProviders;
    }

    public Optional<CloudSummaryBundle> generate(VideoAsset video, List<String> timedTranscriptLines) {
        llmProviders.configureActiveForUserId(video.userId());
        if (!llm.available() || timedTranscriptLines.isEmpty()) {
            return Optional.empty();
        }

        String systemPrompt = """
                你是 OmniVid 的企业级长视频总结助手。你只能基于用户提供的 ASR 字幕内容总结，不能编造字幕外事实。
                必须返回严格 JSON，不要 Markdown，不要代码块。
                JSON 字段固定为 corePoints, meetingMinutes, blogOutline, pptOutline, engineeringInsights，每个字段都是字符串数组。
                每个数组输出 3 到 5 条，保留可追溯时间戳线索。
                """;
        String userPrompt = """
                视频标题：%s

                ASR 字幕：
                %s
                """.formatted(video.originalName(), compactTranscript(timedTranscriptLines));

        return llm.complete(systemPrompt, userPrompt, 1_400)
                .flatMap(this::parseBundle);
    }

    private Optional<CloudSummaryBundle> parseBundle(CloudLlmResult result) {
        try {
            JsonNode root = objectMapper.readTree(stripJsonFence(result.content()));
            List<String> corePoints = readStringArray(root, "corePoints");
            List<String> meetingMinutes = readStringArray(root, "meetingMinutes");
            List<String> blogOutline = readStringArray(root, "blogOutline");
            List<String> pptOutline = readStringArray(root, "pptOutline");
            List<String> engineeringInsights = readStringArray(root, "engineeringInsights");
            if (corePoints.isEmpty() || meetingMinutes.isEmpty() || blogOutline.isEmpty()
                    || pptOutline.isEmpty() || engineeringInsights.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new CloudSummaryBundle(
                    corePoints,
                    meetingMinutes,
                    blogOutline,
                    pptOutline,
                    engineeringInsights,
                    result.model()
            ));
        } catch (Exception exception) {
            return Optional.empty();
        }
    }

    private List<String> readStringArray(JsonNode root, String field) {
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

    private String compactTranscript(List<String> lines) {
        StringBuilder builder = new StringBuilder();
        for (String line : lines) {
            if (builder.length() + line.length() + 1 > MAX_TRANSCRIPT_CHARS) {
                break;
            }
            if (!line.isBlank()) {
                builder.append(line).append('\n');
            }
        }
        return builder.toString().trim();
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
}
