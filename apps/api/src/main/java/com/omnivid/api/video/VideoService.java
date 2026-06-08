package com.omnivid.api.video;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnivid.api.common.ApiException;
import com.omnivid.api.asr.AsrTranscriptSegment;
import com.omnivid.api.asr.AsrTranscriptionException;
import com.omnivid.api.asr.AsrTranscriptionResult;
import com.omnivid.api.asr.WhisperAsrService;
import com.omnivid.api.dedupe.DedupeLock;
import com.omnivid.api.dedupe.DedupeLockService;
import com.omnivid.api.job.ProcessingJob;
import com.omnivid.api.job.ProcessingJobRepository;
import com.omnivid.api.media.AudioExtractionResult;
import com.omnivid.api.media.AudioExtractionException;
import com.omnivid.api.media.FfmpegAudioExtractionService;
import com.omnivid.api.media.VideoMetadataProbeService;
import com.omnivid.api.progress.ProgressCacheService;
import com.omnivid.api.progress.ProgressSnapshot;
import com.omnivid.api.storage.LocalVideoStorageService;
import com.omnivid.api.storage.StoredVideoFile;
import com.omnivid.api.agent.retrieval.TranscriptVectorSearch;
import com.omnivid.api.summary.CloudSummaryBundle;
import com.omnivid.api.summary.CloudSummaryService;
import com.omnivid.api.summary.SummaryAsset;
import com.omnivid.api.summary.SummaryRepository;
import com.omnivid.api.transcript.TranscriptDraft;
import com.omnivid.api.transcript.TranscriptRepository;
import com.omnivid.api.transcript.TranscriptSegment;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class VideoService {
    private static final Logger log = LoggerFactory.getLogger(VideoService.class);
    private static final long DEMO_USER_ID = 1L;

    private final VideoRepository videos;
    private final ProcessingJobRepository jobs;
    private final TranscriptRepository transcripts;
    private final SummaryRepository summaries;
    private final CloudSummaryService cloudSummaryService;
    private final DedupeLockService dedupeLocks;
    private final FfmpegAudioExtractionService audioExtraction;
    private final VideoMetadataProbeService metadataProbe;
    private final WhisperAsrService asr;
    private final LocalVideoStorageService storage;
    private final ObjectMapper objectMapper;
    private final ThreadPoolTaskExecutor processingExecutor;
    private final ProgressCacheService progressCache;
    private final TranscriptVectorSearch vectorSearch;

    public VideoService(
            VideoRepository videos,
            ProcessingJobRepository jobs,
            TranscriptRepository transcripts,
            SummaryRepository summaries,
            CloudSummaryService cloudSummaryService,
            DedupeLockService dedupeLocks,
            FfmpegAudioExtractionService audioExtraction,
            VideoMetadataProbeService metadataProbe,
            WhisperAsrService asr,
            LocalVideoStorageService storage,
            ObjectMapper objectMapper,
            ThreadPoolTaskExecutor omnividProcessingExecutor,
            ProgressCacheService progressCache,
            TranscriptVectorSearch vectorSearch
    ) {
        this.videos = videos;
        this.jobs = jobs;
        this.transcripts = transcripts;
        this.summaries = summaries;
        this.cloudSummaryService = cloudSummaryService;
        this.dedupeLocks = dedupeLocks;
        this.audioExtraction = audioExtraction;
        this.metadataProbe = metadataProbe;
        this.asr = asr;
        this.storage = storage;
        this.objectMapper = objectMapper;
        this.processingExecutor = omnividProcessingExecutor;
        this.progressCache = progressCache;
        this.vectorSearch = vectorSearch;
    }

    @Transactional
    public CompleteUploadResponse completeUpload(CompleteUploadRequest request) {
        String md5 = request.md5().toLowerCase();
        try (DedupeLock lock = dedupeLocks.tryLock(md5, Duration.ofSeconds(30))) {
            if (!lock.acquired()) {
                VideoAsset lockedExisting = videos.findByMd5(md5)
                        .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "Video is being processed, retry later"));
                ProcessingJob job = jobs.findLatestByVideoId(lockedExisting.id())
                        .orElseGet(() -> jobs.create(lockedExisting.id()));
                return new CompleteUploadResponse(true, lockedExisting, job);
            }

            return completeUploadWithLock(
                    md5,
                    request.originalName(),
                    "local://" + md5 + "/" + request.originalName(),
                    request.durationMs(),
                    null
            );
        }
    }

    public CompleteUploadResponse completeStoredUpload(StoredVideoFile storedFile) {
        String md5 = storedFile.md5().toLowerCase();
        try (DedupeLock lock = dedupeLocks.tryLock(md5, Duration.ofSeconds(30))) {
            if (!lock.acquired()) {
                VideoAsset lockedExisting = videos.findByMd5(md5)
                        .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "Video is being processed, retry later"));
                ProcessingJob job = jobs.findLatestByVideoId(lockedExisting.id())
                        .orElseGet(() -> jobs.create(lockedExisting.id()));
                return new CompleteUploadResponse(true, lockedExisting, job);
            }

            long durationMs = metadataProbe.durationMs(storedFile);
            CompleteUploadResponse response = createUploadJobWithLock(md5, storedFile.originalName(), storedFile.storagePath(), durationMs);
            if (!response.deduplicated()) {
                processingExecutor.execute(() -> processStoredVideo(response.video().id(), response.job().id(), storedFile));
            }
            return response;
        }
    }

    private CompleteUploadResponse completeUploadWithLock(
            String md5,
            String originalName,
            String storagePath,
            long durationMs,
            StoredVideoFile storedFile
    ) {
        VideoAsset existing = videos.findByMd5(md5).orElse(null);
        if (existing != null) {
            ProcessingJob job = jobs.findLatestByVideoId(existing.id())
                    .orElseGet(() -> jobs.create(existing.id()));
            return new CompleteUploadResponse(true, existing, job);
        }

        VideoAsset video = videos.insert(
                DEMO_USER_ID,
                md5,
                originalName,
                storagePath,
                durationMs
        );
        ProcessingJob job = jobs.create(video.id());
        String finalStep = "LOCAL_DAG_DONE";
        seedProcessingResult(video, job, finalStep, storedFile, null, null);
        return new CompleteUploadResponse(false, videos.findById(video.id()).orElseThrow(), jobs.findById(job.id()).orElseThrow());
    }

    private CompleteUploadResponse createUploadJobWithLock(
            String md5,
            String originalName,
            String storagePath,
            long durationMs
    ) {
        VideoAsset existing = videos.findByMd5(md5).orElse(null);
        if (existing != null) {
            ProcessingJob job = jobs.findLatestByVideoId(existing.id())
                    .orElseGet(() -> jobs.create(existing.id()));
            return new CompleteUploadResponse(true, existing, job);
        }

        VideoAsset video = videos.insert(
                DEMO_USER_ID,
                md5,
                originalName,
                storagePath,
                durationMs
        );
        ProcessingJob job = jobs.create(video.id());
        cacheProgress(video.id(), job);
        return new CompleteUploadResponse(false, video, job);
    }

    private void processStoredVideo(long videoId, long jobId, StoredVideoFile storedFile) {
        VideoAsset video = videos.findById(videoId).orElseThrow();
        ProcessingJob job = jobs.findById(jobId).orElseThrow();
        AudioExtractionResult audioResult = null;
        AsrTranscriptionResult asrResult = null;
        try {
            audioResult = extractAudio(video, job, storedFile);
            asrResult = transcribeAudio(video.id(), job, storedFile, audioResult);
            seedProcessingResult(video, job, "SUMMARY_GENERATED_AND_LOCAL_DAG_DONE", storedFile, audioResult, asrResult);
        } catch (AudioExtractionException exception) {
            failAudioExtraction(video, job, exception);
        } catch (AsrTranscriptionException exception) {
            failAsrTranscription(video, job, exception);
        } catch (RuntimeException exception) {
            failProcessing(video, job, exception);
        }
    }

    private AudioExtractionResult extractAudio(VideoAsset video, ProcessingJob job, StoredVideoFile storedFile) {
        ProcessingJob current = jobs.findById(job.id()).orElseThrow();
        jobs.advance(current.id(), current.version(), "AUDIO_EXTRACTING", 35, "RUNNING");
        cacheProgress(video.id(), jobs.findById(job.id()).orElseThrow());
        AudioExtractionResult result = audioExtraction.extractToWav(storedFile);
        current = jobs.findById(job.id()).orElseThrow();
        jobs.advance(current.id(), current.version(), "AUDIO_EXTRACTED", 60, "RUNNING");
        cacheProgress(video.id(), jobs.findById(job.id()).orElseThrow());
        return result;
    }

    private AsrTranscriptionResult transcribeAudio(
            long videoId,
            ProcessingJob job,
            StoredVideoFile storedFile,
            AudioExtractionResult audioResult
    ) {
        ProcessingJob current = jobs.findById(job.id()).orElseThrow();
        jobs.advance(current.id(), current.version(), "ASR_TRANSCRIBING", 75, "RUNNING");
        cacheProgress(videoId, jobs.findById(job.id()).orElseThrow());
        AsrTranscriptionResult result = asr.transcribe(storedFile, audioResult);
        current = jobs.findById(job.id()).orElseThrow();
        jobs.advance(current.id(), current.version(), "ASR_TRANSCRIBED", 90, "RUNNING");
        cacheProgress(videoId, jobs.findById(job.id()).orElseThrow());
        return result;
    }

    private CompleteUploadResponse failAudioExtraction(VideoAsset video, ProcessingJob job, AudioExtractionException exception) {
        ProcessingJob current = jobs.findById(job.id()).orElseThrow();
        jobs.fail(current.id(), current.version(), "AUDIO_EXTRACT_FAILED", truncate(exception.getMessage()));
        videos.markFailed(video.id());
        cacheProgress(video.id(), jobs.findById(job.id()).orElseThrow());
        return new CompleteUploadResponse(false, videos.findById(video.id()).orElseThrow(), jobs.findById(job.id()).orElseThrow());
    }

    private CompleteUploadResponse failAsrTranscription(VideoAsset video, ProcessingJob job, AsrTranscriptionException exception) {
        ProcessingJob current = jobs.findById(job.id()).orElseThrow();
        jobs.fail(current.id(), current.version(), "ASR_TRANSCRIBE_FAILED", truncate(exception.getMessage()));
        videos.markFailed(video.id());
        cacheProgress(video.id(), jobs.findById(job.id()).orElseThrow());
        return new CompleteUploadResponse(false, videos.findById(video.id()).orElseThrow(), jobs.findById(job.id()).orElseThrow());
    }

    private void failProcessing(VideoAsset video, ProcessingJob job, RuntimeException exception) {
        ProcessingJob current = jobs.findById(job.id()).orElseThrow();
        jobs.fail(current.id(), current.version(), "LOCAL_DAG_FAILED", truncate(exception.getMessage()));
        videos.markFailed(video.id());
        cacheProgress(video.id(), jobs.findById(job.id()).orElseThrow());
    }

    private String truncate(String message) {
        if (message == null) {
            return "Audio extraction failed";
        }
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }

    public List<VideoAsset> listVideos() {
        return videos.list(DEMO_USER_ID);
    }

    public CompleteUploadResponse retryFailedVideo(long videoId) {
        VideoAsset video = requireVideo(videoId);
        ProcessingJob latestJob = jobs.findLatestByVideoId(videoId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Processing job not found"));
        if (!"FAILED".equals(latestJob.status())) {
            throw retryNotAllowed(latestJob);
        }

        StoredVideoFile storedFile = storage.loadStoredFile(video.storagePath(), video.originalName(), video.md5());
        videos.markProcessing(video.id());
        ProcessingJob retryJob = jobs.createRetry(video.id(), latestJob.retryCount() + 1);
        cacheProgress(video.id(), retryJob);
        processingExecutor.execute(() -> processStoredVideo(video.id(), retryJob.id(), storedFile));
        return new CompleteUploadResponse(false, videos.findById(video.id()).orElseThrow(), retryJob);
    }

    private ApiException retryNotAllowed(ProcessingJob job) {
        String suggestion = switch (job.status()) {
            case "DONE" -> "该视频已经解析完成，不需要进入补偿队列。重复上传同一文件会通过 MD5 去重直接复用结果。";
            case "RUNNING" -> "该视频仍在解析中，请等待 SSE 进度完成；如果最终失败，Recovery Queue 会出现可重试项。";
            default -> "只有最新 processing_job.status=FAILED 的任务允许重试，请先刷新视频详情和 Recovery Queue。";
        };
        return new ApiException(
                HttpStatus.CONFLICT,
                "Only failed processing jobs can be retried",
                suggestion,
                "job#" + job.id()
                        + ", status=" + job.status()
                        + ", step=" + job.currentStep()
                        + ", retryCount=" + job.retryCount()
        );
    }

    public VideoDetailResponse detail(long videoId) {
        VideoAsset video = requireVideo(videoId);
        ProcessingJob job = jobs.findLatestByVideoId(videoId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Processing job not found"));
        List<TranscriptSegment> videoTranscripts = transcripts.listByVideoId(videoId);
        if ("DONE".equals(job.status())) {
            ensureSummaryAssetsFromTranscripts(video, videoTranscripts);
        }
        return new VideoDetailResponse(
                video,
                job,
                videoTranscripts,
                summaries.listByVideoId(videoId)
        );
    }

    public VideoAsset requireVideo(long videoId) {
        return videos.findById(videoId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Video not found"));
    }

    public List<TranscriptSegment> transcripts(long videoId) {
        requireVideo(videoId);
        return transcripts.listByVideoId(videoId);
    }

    public List<TranscriptSegment> searchTranscripts(long videoId, String keyword) {
        requireVideo(videoId);
        String normalized = keyword == null ? "" : keyword.trim();
        if (normalized.isBlank()) {
            return List.of();
        }
        return transcripts.search(videoId, normalized);
    }

    public List<SummaryAsset> summaries(long videoId) {
        VideoAsset video = requireVideo(videoId);
        ProcessingJob job = jobs.findLatestByVideoId(videoId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Processing job not found"));
        if ("DONE".equals(job.status())) {
            ensureSummaryAssetsFromTranscripts(video, transcripts.listByVideoId(videoId));
        }
        return summaries.listByVideoId(videoId);
    }

    public ProgressSnapshot progress(long videoId) {
        requireVideo(videoId);
        return progressCache.get(videoId)
                .orElseGet(() -> {
                    ProcessingJob job = jobs.findLatestByVideoId(videoId)
                            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Processing job not found"));
                    return snapshot(videoId, job);
                });
    }

    public SseEmitter progressStream(long videoId) {
        requireVideo(videoId);
        SseEmitter emitter = new SseEmitter(Duration.ofMinutes(2).toMillis());
        Thread.startVirtualThread(() -> {
            try {
                while (true) {
                    ProgressSnapshot snapshot = progress(videoId);
                    emitter.send(SseEmitter.event().name("progress").data(snapshot));
                    if (!"RUNNING".equals(snapshot.status())) {
                        emitter.complete();
                        return;
                    }
                    Thread.sleep(500);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                emitter.completeWithError(exception);
            } catch (Exception exception) {
                emitter.completeWithError(exception);
            }
        });
        return emitter;
    }

    private void seedProcessingResult(
            VideoAsset video,
            ProcessingJob job,
            String finalStep,
            StoredVideoFile storedFile,
            AudioExtractionResult audioResult,
            AsrTranscriptionResult asrResult
    ) {
        if (!transcripts.existsByVideoId(video.id())) {
            transcripts.insertBatch(video.id(), buildTranscript(video, storedFile, audioResult, asrResult));
        }
        indexVideoTranscripts(video.id());

        ensureSummaryAssetsFromAsr(video, asrResult);

        ProcessingJob current = jobs.findById(job.id()).orElseThrow();
        jobs.advance(current.id(), current.version(), finalStep, 100, "DONE");
        cacheProgress(video.id(), jobs.findById(job.id()).orElseThrow());
        videos.markReady(video.id());
    }

    private void indexVideoTranscripts(long videoId) {
        try {
            List<TranscriptSegment> segments = transcripts.listByVideoId(videoId);
            TranscriptVectorSearch.RebuildResult result = vectorSearch.rebuildIndex(segments);
            if (!result.success()) {
                log.info("Vector index skipped for video {}: {}", videoId, result.message());
            }
        } catch (RuntimeException exception) {
            log.warn("Vector index failed for video {}, keeping video processing successful: {}", videoId, exception.getMessage());
        }
    }

    private void cacheProgress(long videoId, ProcessingJob job) {
        progressCache.put(snapshot(videoId, job));
    }

    private ProgressSnapshot snapshot(long videoId, ProcessingJob job) {
        return new ProgressSnapshot(videoId, job.id(), job.currentStep(), job.status(), job.progress());
    }

    private List<TranscriptDraft> buildProcessingTranscript(
            VideoAsset video,
            StoredVideoFile storedFile,
            AudioExtractionResult audioResult
    ) {
        String fileInfo = storedFile == null
                ? "已通过兼容接口登记视频：" + video.originalName() + "。"
                : "已接收本地视频文件：" + video.originalName() + "，大小 " + storedFile.sizeBytes() + " bytes。";
        String audioInfo = audioResult == null
                ? "当前请求没有执行真实 ffmpeg 抽音频。"
                : "ffmpeg 已抽取音频：" + audioResult.audioPath() + "，大小 " + audioResult.sizeBytes() + " bytes。";

        return List.of(
                new TranscriptDraft(0, 0, 1_000, "System", fileInfo, tokenCount(fileInfo)),
                new TranscriptDraft(1, 1_000, 2_000, "System", "视频 MD5 已计算完成：" + video.md5() + "，重复上传会直接复用该记录。", 20),
                new TranscriptDraft(2, 2_000, 3_000, "System", audioInfo, tokenCount(audioInfo)),
                new TranscriptDraft(3, 3_000, 4_000, "System", "本地 ASR 已执行，但没有识别到有效语音字幕。请上传包含清晰人声的视频。", 24)
        );
    }

    private List<TranscriptDraft> buildTranscript(
            VideoAsset video,
            StoredVideoFile storedFile,
            AudioExtractionResult audioResult,
            AsrTranscriptionResult asrResult
    ) {
        if (asrResult == null || asrResult.segments().isEmpty()) {
            return buildProcessingTranscript(video, storedFile, audioResult);
        }

        List<TranscriptDraft> drafts = new ArrayList<>();
        for (int index = 0; index < asrResult.segments().size(); index++) {
            AsrTranscriptSegment segment = asrResult.segments().get(index);
            drafts.add(new TranscriptDraft(
                    index,
                    segment.startMs(),
                    segment.endMs(),
                    "ASR",
                    segment.text(),
                    tokenCount(segment.text())
            ));
        }
        return drafts;
    }

    private void ensureSummaryAssetsFromAsr(VideoAsset video, AsrTranscriptionResult asrResult) {
        ensureSummaryAssets(video, sourceSegments(asrResult));
    }

    private void ensureSummaryAssetsFromTranscripts(VideoAsset video, List<TranscriptSegment> segments) {
        ensureSummaryAssets(video, sourceSegments(segments));
    }

    private void ensureSummaryAssets(VideoAsset video, List<SummarySourceSegment> segments) {
        if (!summaries.existsByVideoIdAndPromptVersion(video.id(), "llm-v1") && insertCloudSummaryAssets(video, segments)) {
            return;
        }

        summaries.insertIfAbsent(video.id(), "CORE_POINTS", "ASR 核心观点",
                summaryJson("points", buildCorePoints(video, segments)));
        summaries.insertIfAbsent(video.id(), "MEETING_MINUTES", "会议纪要",
                summaryJson("items", buildMeetingMinutes(video, segments)));
        summaries.insertIfAbsent(video.id(), "BLOG_OUTLINE", "博客大纲",
                summaryJson("items", buildBlogOutline(video, segments)));
        summaries.insertIfAbsent(video.id(), "PPT_OUTLINE", "PPT 大纲",
                summaryJson("items", buildPptOutline(video, segments)));
        summaries.insertIfAbsent(video.id(), "INTERVIEW_HOOKS", "面试钩子",
                summaryJson("hooks", buildInterviewHooks(segments)));
    }

    private boolean insertCloudSummaryAssets(VideoAsset video, List<SummarySourceSegment> segments) {
        Optional<CloudSummaryBundle> generated = cloudSummaryService.generate(video, timedTranscriptLines(segments));
        if (generated.isEmpty()) {
            return false;
        }

        CloudSummaryBundle bundle = generated.get();
        String modelName = bundle.modelName();
        summaries.replaceByType(video.id(), "CORE_POINTS", "LLM 核心观点",
                summaryJson("points", bundle.corePoints()), modelName, "llm-v1");
        summaries.replaceByType(video.id(), "MEETING_MINUTES", "LLM 会议纪要",
                summaryJson("items", bundle.meetingMinutes()), modelName, "llm-v1");
        summaries.replaceByType(video.id(), "BLOG_OUTLINE", "LLM 博客大纲",
                summaryJson("items", bundle.blogOutline()), modelName, "llm-v1");
        summaries.replaceByType(video.id(), "PPT_OUTLINE", "LLM PPT 大纲",
                summaryJson("items", bundle.pptOutline()), modelName, "llm-v1");
        summaries.replaceByType(video.id(), "INTERVIEW_HOOKS", "LLM 面试钩子",
                summaryJson("hooks", bundle.interviewHooks()), modelName, "llm-v1");
        return true;
    }

    private List<SummarySourceSegment> sourceSegments(AsrTranscriptionResult asrResult) {
        if (asrResult == null || asrResult.segments().isEmpty()) {
            return List.of();
        }
        return asrResult.segments().stream()
                .map(segment -> new SummarySourceSegment(segment.startMs(), segment.endMs(), segment.text()))
                .toList();
    }

    private List<SummarySourceSegment> sourceSegments(List<TranscriptSegment> segments) {
        return segments.stream()
                .map(segment -> new SummarySourceSegment(segment.startMs(), segment.endMs(), segment.content()))
                .toList();
    }

    private List<String> buildCorePoints(VideoAsset video, List<SummarySourceSegment> segments) {
        if (segments.isEmpty()) {
            return List.of(
                "视频：" + video.originalName(),
                "本地 ASR 已执行，但没有识别到有效语音字幕",
                "建议上传包含清晰人声的视频后重新生成总结",
                    "云端 LLM 未启用或不可用时，会使用本地总结器兜底"
            );
        }

        SummarySourceSegment first = segments.getFirst();
        return List.of(
                "视频：" + video.originalName(),
                "字幕内容概览：" + compact(joinTranscriptText(segments), 220),
                "可追溯片段：" + formatTimeRange(first) + " " + compact(first.text(), 120),
                "当前为 ASR 字幕驱动的结构化总结，云端 LLM 可通过页面或环境变量启用"
        );
    }

    private List<String> buildMeetingMinutes(VideoAsset video, List<SummarySourceSegment> segments) {
        if (segments.isEmpty()) {
            return List.of(
                    "会议主题：" + video.originalName(),
                    "讨论结论：未识别到有效语音，暂不能形成真实纪要",
                    "待办事项：重新上传清晰人声视频后再生成会议纪要"
            );
        }

        SummarySourceSegment first = segments.getFirst();
        return List.of(
                "会议主题：" + video.originalName(),
                "讨论摘要：" + compact(joinTranscriptText(segments), 180),
                "关键证据：" + formatTimeRange(first) + " " + compact(first.text(), 120),
                "待办事项：复看高价值时间片段，并按需接入真实 LLM 生成责任人与截止时间"
        );
    }

    private List<String> buildBlogOutline(VideoAsset video, List<SummarySourceSegment> segments) {
        if (segments.isEmpty()) {
            return List.of(
                    "标题：" + video.originalName() + " 的结构化整理",
                    "开篇：说明视频已解析但没有有效字幕",
                    "主体：补充清晰语音后再展开观点、案例和结论",
                    "结尾：当前结果来自本地模板总结器"
            );
        }

        return List.of(
                "标题：" + video.originalName() + " 的结构化整理",
                "开篇：从视频字幕中抽取主要问题和场景",
                "主体一：" + compact(segments.getFirst().text(), 110),
                "主体二：" + compact(joinTranscriptText(segments), 170),
                "结尾：保留时间戳引用，方便回看原视频"
        );
    }

    private List<String> buildPptOutline(VideoAsset video, List<SummarySourceSegment> segments) {
        if (segments.isEmpty()) {
            return List.of(
                    "封面：" + video.originalName(),
                    "第 1 页：视频解析状态与空字幕原因",
                    "第 2 页：上传、MD5 去重、ffmpeg、ASR 的处理链路",
                    "第 3 页：下一步接入真实 LLM 后生成演讲稿"
            );
        }

        SummarySourceSegment first = segments.getFirst();
        return List.of(
                "封面：" + video.originalName(),
                "第 1 页：背景与问题 - " + compact(first.text(), 100),
                "第 2 页：核心内容 - " + compact(joinTranscriptText(segments), 150),
                "第 3 页：证据引用 - " + formatTimeRange(first),
                "第 4 页：下一步 - 接入真实 LLM 后生成演讲稿和版式"
        );
    }

    private List<String> buildInterviewHooks(List<SummarySourceSegment> segments) {
        if (segments.isEmpty()) {
            return List.of(
                    "ASR：可展开音频质量、模型大小、VAD 与空字幕兜底",
                    "ffmpeg：可展开抽音频、子进程超时、标准输出阻塞",
                    "任务状态机：可展开失败重试、幂等和状态一致性"
            );
        }

        String lowerText = joinTranscriptText(segments).toLowerCase(Locale.ROOT);
        List<String> hooks = new ArrayList<>();
        if (containsAny(lowerText, "mysql", "my sql", "sql", "database", "status")) {
            hooks.add("MySQL：围绕任务状态、MD5 唯一索引、事务和乐观锁展开");
        }
        if (containsAny(lowerText, "redis", "readies", "cache", "progress")) {
            hooks.add("Redis：围绕上传进度缓存、防重复提交、热点 Key 和一致性展开");
        }
        if (containsAny(lowerText, "agent", "rag", "embedding", "vector", "answer")) {
            hooks.add("AI Agent：围绕字幕检索、时间戳引用、召回重排和防幻觉展开");
        }
        if (containsAny(lowerText, "thread", "queue", "dag", "async", "retry")) {
            hooks.add("Java 并发：围绕本地 DAG、线程池参数、拒绝策略和失败重试展开");
        }
        if (hooks.isEmpty()) {
            hooks.add("ASR：围绕真实字幕生成、时间戳对齐和空结果兜底展开");
            hooks.add("检索：围绕 video_id + start_ms 联合索引和时间轴定位展开");
            hooks.add("后端链路：围绕上传、去重、抽音频、ASR、总结状态流转展开");
        }
        return hooks;
    }

    private String summaryJson(String key, List<String> items) {
        try {
            return objectMapper.writeValueAsString(Map.of(key, items));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize summary", exception);
        }
    }

    private String joinTranscriptText(List<SummarySourceSegment> segments) {
        StringBuilder builder = new StringBuilder();
        for (SummarySourceSegment segment : segments) {
            if (segment.text().isBlank()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(segment.text().trim());
        }
        return builder.toString();
    }

    private List<String> timedTranscriptLines(List<SummarySourceSegment> segments) {
        return segments.stream()
                .filter(segment -> !segment.text().isBlank())
                .map(segment -> formatTimeRange(segment) + " " + segment.text().trim())
                .toList();
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String compact(String text, int maxLength) {
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.isBlank()) {
            return "未识别到可用文本";
        }
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength - 1) + "…";
    }

    private String formatTimeRange(SummarySourceSegment segment) {
        return formatTime(segment.startMs()) + "-" + formatTime(segment.endMs());
    }

    private String formatTime(long ms) {
        long totalSeconds = ms / 1000;
        return "%02d:%02d".formatted(totalSeconds / 60, totalSeconds % 60);
    }

    private int tokenCount(String content) {
        return Math.max(1, content.length() / 2);
    }

    private record SummarySourceSegment(long startMs, long endMs, String text) {
    }
}
