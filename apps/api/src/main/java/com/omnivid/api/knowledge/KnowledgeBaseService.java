package com.omnivid.api.knowledge;

import com.omnivid.api.common.ApiException;
import com.omnivid.api.transcript.TranscriptRepository;
import com.omnivid.api.transcript.TranscriptSegment;
import com.omnivid.api.video.VideoService;
import com.omnivid.api.video.VideoAsset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeBaseService {
    private final KnowledgeBaseRepository knowledgeBases;
    private final VideoService videos;
    private final TranscriptRepository transcripts;

    public KnowledgeBaseService(
            KnowledgeBaseRepository knowledgeBases,
            VideoService videos,
            TranscriptRepository transcripts
    ) {
        this.knowledgeBases = knowledgeBases;
        this.videos = videos;
        this.transcripts = transcripts;
    }

    public List<KnowledgeBase> list() {
        return knowledgeBases.list();
    }

    public KnowledgeBase create(KnowledgeBaseCreateRequest request) {
        String name = requireName(request.name());
        String description = request.description() == null ? "" : request.description().trim();
        return knowledgeBases.create(name, description);
    }

    public KnowledgeBaseDetailResponse detail(long id) {
        KnowledgeBase knowledgeBase = requireKnowledgeBase(id);
        return new KnowledgeBaseDetailResponse(knowledgeBase, knowledgeBases.videos(id));
    }

    public KnowledgeBaseCoverageResponse coverage(long id) {
        KnowledgeBase knowledgeBase = requireKnowledgeBase(id);
        List<VideoAsset> assets = knowledgeBases.videos(id);
        List<KnowledgeBaseCoverageVideoResponse> rows = assets.stream()
                .map(video -> {
                    List<TranscriptSegment> videoTranscripts = transcripts.listByVideoId(video.id());
                    long firstStartMs = videoTranscripts.isEmpty() ? 0 : videoTranscripts.getFirst().startMs();
                    long lastEndMs = videoTranscripts.isEmpty() ? 0 : videoTranscripts.getLast().endMs();
                    return new KnowledgeBaseCoverageVideoResponse(
                            video.id(),
                            video.originalName(),
                            video.status(),
                            video.durationMs(),
                            videoTranscripts.size(),
                            firstStartMs,
                            lastEndMs
                    );
                })
                .toList();
        int transcriptCount = rows.stream().mapToInt(KnowledgeBaseCoverageVideoResponse::transcriptCount).sum();
        int readyVideoCount = (int) rows.stream().filter(row -> "READY".equals(row.status())).count();
        long totalDurationMs = rows.stream().mapToLong(KnowledgeBaseCoverageVideoResponse::durationMs).sum();
        String summary = "%d videos, %d ready, %d transcript segments".formatted(
                rows.size(),
                readyVideoCount,
                transcriptCount
        );
        return new KnowledgeBaseCoverageResponse(
                knowledgeBase,
                rows.size(),
                readyVideoCount,
                transcriptCount,
                totalDurationMs,
                summary,
                rows
        );
    }

    public KnowledgeBaseCompareResponse compare(long id, KnowledgeBaseCompareRequest request) {
        KnowledgeBase knowledgeBase = requireKnowledgeBase(id);
        List<VideoAsset> assets = knowledgeBases.videos(id);
        if (assets.size() < 2) {
            throw new ApiException(HttpStatus.CONFLICT, "At least two videos are required for knowledge base comparison");
        }

        String question = request == null || request.question() == null || request.question().isBlank()
                ? "对比多个视频的核心观点差异"
                : request.question().trim();
        List<String> terms = queryTerms(question);
        List<KnowledgeBaseVideoViewpoint> viewpoints = new ArrayList<>();
        List<KnowledgeBaseCompareCitation> citations = new ArrayList<>();
        Map<Long, List<TranscriptSegment>> byVideo = transcripts.listByVideoIds(assets.stream().map(VideoAsset::id).toList())
                .stream()
                .collect(Collectors.groupingBy(TranscriptSegment::videoId));

        for (VideoAsset video : assets) {
            List<TranscriptSegment> selected = selectViewpointSegments(byVideo.getOrDefault(video.id(), List.of()), terms);
            List<KnowledgeBaseCompareCitation> videoCitations = selected.stream()
                    .map(segment -> citation(video, segment, score(segment.content(), terms)))
                    .toList();
            citations.addAll(videoCitations);
            viewpoints.add(new KnowledgeBaseVideoViewpoint(
                    video.id(),
                    video.originalName(),
                    buildViewpoint(video, selected),
                    videoCitations
            ));
        }

        return new KnowledgeBaseCompareResponse(
                knowledgeBase.id(),
                knowledgeBase.name(),
                question,
                assets.size(),
                sharedThemes(viewpoints),
                differences(viewpoints),
                viewpoints,
                citations
        );
    }

    public KnowledgeBaseDetailResponse addVideo(long id, KnowledgeBaseVideoRequest request) {
        requireKnowledgeBase(id);
        videos.requireVideo(request.videoId());
        knowledgeBases.addVideo(id, request.videoId());
        return detail(id);
    }

    public KnowledgeBaseDetailResponse removeVideo(long id, long videoId) {
        requireKnowledgeBase(id);
        knowledgeBases.removeVideo(id, videoId);
        return detail(id);
    }

    public void delete(long id) {
        requireKnowledgeBase(id);
        knowledgeBases.delete(id);
    }

    public List<Long> videoIds(long id) {
        requireKnowledgeBase(id);
        return knowledgeBases.videoIds(id);
    }

    public KnowledgeBase requireKnowledgeBase(long id) {
        return knowledgeBases.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Knowledge base not found"));
    }

    private List<TranscriptSegment> selectViewpointSegments(List<TranscriptSegment> segments, List<String> terms) {
        if (segments.isEmpty()) {
            return List.of();
        }
        return segments.stream()
                .filter(segment -> segment.content() != null && segment.content().trim().length() >= 6)
                .sorted(Comparator
                        .comparingInt((TranscriptSegment segment) -> score(segment.content(), terms)).reversed()
                        .thenComparingLong(TranscriptSegment::startMs))
                .limit(3)
                .toList();
    }

    private KnowledgeBaseCompareCitation citation(VideoAsset video, TranscriptSegment segment, int score) {
        return new KnowledgeBaseCompareCitation(
                video.originalName() + " " + formatTime(segment.startMs()) + "-" + formatTime(segment.endMs()),
                video.id(),
                segment.id(),
                segment.startMs(),
                segment.endMs(),
                score,
                compact(segment.content(), 110)
        );
    }

    private String buildViewpoint(VideoAsset video, List<TranscriptSegment> selected) {
        if (selected.isEmpty()) {
            return "《" + video.originalName() + "》暂时没有可用于对比的字幕片段。";
        }
        return "《" + video.originalName() + "》主要围绕：" + selected.stream()
                .map(segment -> compact(segment.content(), 72))
                .collect(Collectors.joining("；"));
    }

    private List<String> sharedThemes(List<KnowledgeBaseVideoViewpoint> viewpoints) {
        String all = viewpoints.stream()
                .map(KnowledgeBaseVideoViewpoint::viewpoint)
                .collect(Collectors.joining(" "))
                .toLowerCase(Locale.ROOT);
        List<String> themes = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : themeDictionary().entrySet()) {
            if (entry.getValue().stream().anyMatch(all::contains)) {
                themes.add("共同涉及：" + entry.getKey());
            }
        }
        if (themes.isEmpty()) {
            themes.add("共同点需要结合更多字幕证据继续追问。");
        }
        return themes.stream().limit(5).toList();
    }

    private List<String> differences(List<KnowledgeBaseVideoViewpoint> viewpoints) {
        return viewpoints.stream()
                .limit(6)
                .map(viewpoint -> "《" + viewpoint.originalName() + "》侧重：" + compact(viewpoint.viewpoint(), 120))
                .toList();
    }

    private Map<String, List<String>> themeDictionary() {
        return Map.of(
                "MySQL / 数据一致性", List.of("mysql", "sql", "事务", "索引", "唯一"),
                "Redis / 缓存与限流", List.of("redis", "缓存", "限流", "锁", "setnx"),
                "ASR / OCR 字幕质量", List.of("asr", "ocr", "字幕", "语音", "识别"),
                "RAG / 向量检索", List.of("rag", "embedding", "vector", "qdrant", "向量", "检索"),
                "Agent / 工具调用", List.of("agent", "工具", "引用", "问答")
        );
    }

    private List<String> queryTerms(String question) {
        String normalized = question.toLowerCase(Locale.ROOT);
        List<String> terms = new ArrayList<>();
        for (String raw : normalized.split("[^a-z0-9\\u4e00-\\u9fa5]+")) {
            if (raw.length() >= 2) {
                terms.add(raw);
            }
        }
        if (normalized.contains("mysql") || normalized.contains("数据库")) {
            terms.addAll(List.of("mysql", "sql", "事务", "索引"));
        }
        if (normalized.contains("redis") || normalized.contains("缓存")) {
            terms.addAll(List.of("redis", "缓存", "锁", "限流"));
        }
        if (normalized.contains("字幕") || normalized.contains("asr") || normalized.contains("ocr")) {
            terms.addAll(List.of("字幕", "asr", "ocr", "识别"));
        }
        if (normalized.contains("agent") || normalized.contains("rag") || normalized.contains("向量")) {
            terms.addAll(List.of("agent", "rag", "向量", "检索", "引用"));
        }
        return terms.stream().distinct().toList();
    }

    private int score(String content, List<String> terms) {
        String text = content == null ? "" : content.toLowerCase(Locale.ROOT);
        int score = 0;
        for (String term : terms) {
            if (!term.isBlank() && text.contains(term.toLowerCase(Locale.ROOT))) {
                score += Math.max(2, term.length());
            }
        }
        return score;
    }

    private String formatTime(long ms) {
        long totalSeconds = ms / 1000;
        return "%02d:%02d".formatted(totalSeconds / 60, totalSeconds % 60);
    }

    private String compact(String text, int maxLength) {
        String normalized = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength - 1) + "...";
    }

    private String requireName(String value) {
        if (value == null || value.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Knowledge base name is required");
        }
        return value.trim();
    }
}
