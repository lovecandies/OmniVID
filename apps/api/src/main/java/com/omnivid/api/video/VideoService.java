package com.omnivid.api.video;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnivid.api.common.ApiException;
import com.omnivid.api.asr.BurnedSubtitleOcrService;
import com.omnivid.api.asr.AsrTranscriptSegment;
import com.omnivid.api.asr.AsrTranscriptionException;
import com.omnivid.api.asr.AsrTranscriptionResult;
import com.omnivid.api.asr.OcrSubtitleQualityResponse;
import com.omnivid.api.asr.TranscriptRepairResponse;
import com.omnivid.api.asr.WhisperAsrService;
import com.omnivid.api.dedupe.DedupeLock;
import com.omnivid.api.dedupe.DedupeLockService;
import com.omnivid.api.agent.cache.AgentAnswerCache;
import com.omnivid.api.knowledge.KnowledgeBaseRepository;
import com.omnivid.api.job.ProcessingJob;
import com.omnivid.api.job.ProcessingJobRepository;
import com.omnivid.api.job.mq.ProcessingCommand;
import com.omnivid.api.job.mq.ProcessingDispatchService;
import com.omnivid.api.observability.TraceContext;
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
import com.omnivid.api.transcript.TranscriptEditRequest;
import com.omnivid.api.transcript.TranscriptContextRepairService;
import com.omnivid.api.transcript.TranscriptRepository;
import com.omnivid.api.transcript.TranscriptSegment;
import com.omnivid.api.transcript.TranscriptSnapshotSegment;
import com.omnivid.api.transcript.TranscriptVersion;
import com.omnivid.api.transcript.TranscriptVersionDetailResponse;
import com.omnivid.api.transcript.TranscriptVersionRepository;
import com.omnivid.api.transcript.TranscriptVersionResponse;
import com.omnivid.api.transcript.TranscriptVersionSegmentResponse;
import com.omnivid.api.transcript.SubtitleTextSanitizer;
import com.omnivid.api.transcript.TermGlossaryService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
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
    private final TranscriptVersionRepository transcriptVersions;
    private final KnowledgeBaseRepository knowledgeBases;
    private final SummaryRepository summaries;
    private final CloudSummaryService cloudSummaryService;
    private final DedupeLockService dedupeLocks;
    private final FfmpegAudioExtractionService audioExtraction;
    private final VideoMetadataProbeService metadataProbe;
    private final WhisperAsrService asr;
    private final BurnedSubtitleOcrService burnedSubtitleOcr;
    private final LocalVideoStorageService storage;
    private final ObjectMapper objectMapper;
    private final ProcessingDispatchService processingDispatch;
    private final ProgressCacheService progressCache;
    private final AgentAnswerCache answerCache;
    private final TranscriptVectorSearch vectorSearch;
    private final TranscriptContextRepairService contextRepairService;
    private final SubtitleTextSanitizer sanitizer;
    private final TermGlossaryService termGlossary;
    private final boolean ocrAutoFusionEnabled;
    private final String ocrAutoFusionMode;
    private final String processingMode;

    public VideoService(
            VideoRepository videos,
            ProcessingJobRepository jobs,
            TranscriptRepository transcripts,
            TranscriptVersionRepository transcriptVersions,
            KnowledgeBaseRepository knowledgeBases,
            SummaryRepository summaries,
            CloudSummaryService cloudSummaryService,
            DedupeLockService dedupeLocks,
            FfmpegAudioExtractionService audioExtraction,
            VideoMetadataProbeService metadataProbe,
            WhisperAsrService asr,
            BurnedSubtitleOcrService burnedSubtitleOcr,
            LocalVideoStorageService storage,
            ObjectMapper objectMapper,
            ProcessingDispatchService processingDispatch,
            ProgressCacheService progressCache,
            AgentAnswerCache answerCache,
            TranscriptVectorSearch vectorSearch,
            TranscriptContextRepairService contextRepairService,
            SubtitleTextSanitizer sanitizer,
            TermGlossaryService termGlossary,
            @Value("${omnivid.ocr.auto-fusion-enabled:true}") boolean ocrAutoFusionEnabled,
            @Value("${omnivid.ocr.auto-fusion-mode:conservative}") String ocrAutoFusionMode,
            @Value("${omnivid.processing.mode:local}") String processingMode
    ) {
        this.videos = videos;
        this.jobs = jobs;
        this.transcripts = transcripts;
        this.transcriptVersions = transcriptVersions;
        this.knowledgeBases = knowledgeBases;
        this.summaries = summaries;
        this.cloudSummaryService = cloudSummaryService;
        this.dedupeLocks = dedupeLocks;
        this.audioExtraction = audioExtraction;
        this.metadataProbe = metadataProbe;
        this.asr = asr;
        this.burnedSubtitleOcr = burnedSubtitleOcr;
        this.storage = storage;
        this.objectMapper = objectMapper;
        this.processingDispatch = processingDispatch;
        this.progressCache = progressCache;
        this.answerCache = answerCache;
        this.vectorSearch = vectorSearch;
        this.contextRepairService = contextRepairService;
        this.sanitizer = sanitizer;
        this.termGlossary = termGlossary;
        this.ocrAutoFusionEnabled = ocrAutoFusionEnabled;
        this.ocrAutoFusionMode = ocrAutoFusionMode == null ? "conservative" : ocrAutoFusionMode.trim().toLowerCase(Locale.ROOT);
        this.processingMode = processingMode == null ? "local" : processingMode.trim().toLowerCase(Locale.ROOT);
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

    @Transactional
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
                processingDispatch.dispatch(new ProcessingCommand(response.video().id(), response.job().id(), false));
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
        String finalStep = finalProcessingStep();
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

    public boolean runProcessingCommand(ProcessingCommand command) {
        try (TraceContext.Scope ignored = TraceContext.open(command.traceId(), Map.of(
                "videoId", command.videoId(),
                "jobId", command.jobId()
        ))) {
            log.info("video_processing_started");
            VideoAsset video = requireVideo(command.videoId());
            StoredVideoFile storedFile = storage.loadStoredFile(video.storagePath(), video.originalName(), video.md5());
            boolean completed = processStoredVideo(video.id(), command.jobId(), storedFile, command.replaceExistingTranscripts());
            log.info(completed ? "video_processing_completed" : "video_processing_failed");
            return completed;
        }
    }

    private boolean processStoredVideo(long videoId, long jobId, StoredVideoFile storedFile, boolean replaceExistingTranscripts) {
        VideoAsset video = videos.findById(videoId).orElseThrow();
        ProcessingJob job = jobs.findById(jobId).orElseThrow();
        AudioExtractionResult audioResult = null;
        AsrTranscriptionResult asrResult = null;
        try {
            audioResult = extractAudio(video, job, storedFile);
            asrResult = transcribeAudio(video.id(), job, storedFile, audioResult);
            if (replaceExistingTranscripts && (asrResult == null || asrResult.segments().isEmpty())) {
                throw new AsrTranscriptionException("ASR produced no valid subtitles; existing subtitles were kept");
            }
            seedProcessingResult(video, job, finalProcessingStep(), storedFile, audioResult, asrResult, replaceExistingTranscripts);
            return true;
        } catch (AudioExtractionException exception) {
            failAudioExtraction(video, job, exception);
        } catch (AsrTranscriptionException exception) {
            failAsrTranscription(video, job, exception);
        } catch (RuntimeException exception) {
            failProcessing(video, job, exception);
        }
        return false;
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
        jobs.fail(current.id(), current.version(), processingFailureStep(), truncate(exception.getMessage()));
        videos.markFailed(video.id());
        cacheProgress(video.id(), jobs.findById(job.id()).orElseThrow());
    }

    private String truncate(String message) {
        if (message == null) {
            return "Audio extraction failed";
        }
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }

    private String finalProcessingStep() {
        return "rocketmq".equals(processingMode)
                ? "SUMMARY_GENERATED_AND_ROCKETMQ_DAG_DONE"
                : "SUMMARY_GENERATED_AND_LOCAL_DAG_DONE";
    }

    private String processingFailureStep() {
        return "rocketmq".equals(processingMode) ? "ROCKETMQ_DAG_FAILED" : "LOCAL_DAG_FAILED";
    }

    public List<VideoAsset> listVideos() {
        return videos.list(DEMO_USER_ID);
    }

    @Transactional
    public CompleteUploadResponse retryFailedVideo(long videoId) {
        VideoAsset video = requireVideo(videoId);
        ProcessingJob latestJob = jobs.findLatestByVideoId(videoId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Processing job not found"));
        if (!"FAILED".equals(latestJob.status())) {
            throw retryNotAllowed(latestJob);
        }

        storage.loadStoredFile(video.storagePath(), video.originalName(), video.md5());
        videos.markProcessing(video.id());
        ProcessingJob retryJob = jobs.createRetry(video.id(), latestJob.retryCount() + 1);
        cacheProgress(video.id(), retryJob);
        processingDispatch.dispatch(new ProcessingCommand(video.id(), retryJob.id(), false));
        return new CompleteUploadResponse(false, videos.findById(video.id()).orElseThrow(), retryJob);
    }

    @Transactional
    public CompleteUploadResponse reprocessAsr(long videoId) {
        VideoAsset video = requireVideo(videoId);
        ProcessingJob latestJob = jobs.findLatestByVideoId(videoId).orElse(null);
        if (latestJob != null && "RUNNING".equals(latestJob.status())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "Video is already being processed",
                    "请等待当前解析任务完成后再重新识别字幕。",
                    "job#" + latestJob.id() + ", step=" + latestJob.currentStep()
            );
        }

        storage.loadStoredFile(video.storagePath(), video.originalName(), video.md5());
        videos.markProcessing(video.id());
        ProcessingJob reprocessJob = jobs.createAsrReprocess(video.id(), latestJob == null ? 0 : latestJob.retryCount() + 1);
        cacheProgress(video.id(), reprocessJob);
        processingDispatch.dispatch(new ProcessingCommand(video.id(), reprocessJob.id(), true));
        return new CompleteUploadResponse(false, videos.findById(video.id()).orElseThrow(), reprocessJob);
    }

    public TranscriptRepairResponse repairTranscriptEncoding(long videoId) {
        VideoAsset video = requireVideo(videoId);
        int scanned = transcripts.countByVideoId(videoId);
        int repairedTranscripts = transcripts.repairEncodingByVideoId(videoId);
        int contextRepaired = repairTranscriptContext(videoId);
        repairedTranscripts += contextRepaired;
        repairedTranscripts += applyTermGlossary(videoId);
        int repairedSummaries = summaries.repairEncodingByVideoId(videoId);
        boolean reindexed = false;
        if (repairedTranscripts > 0) {
            rebuildDerivedAssetsFromTranscripts(videoId);
            reindexed = true;
        }
        int repaired = repairedTranscripts + repairedSummaries;
        return new TranscriptRepairResponse(
                videoId,
                scanned,
                repaired,
                reindexed,
                repaired == 0
                        ? "No subtitle text issues found"
                        : "Subtitle encoding, technical terms or context-level ASR text repaired"
        );
    }

    private int repairTranscriptContext(long videoId) {
        int updated = 0;
        List<TranscriptSegment> currentSegments = transcripts.listByVideoId(videoId);
        for (TranscriptContextRepairService.RepairPatch patch : contextRepairService.buildRepairPlan(currentSegments)) {
            updated += transcripts.updateContentById(patch.segmentId(), patch.text());
        }
        return updated;
    }

    private int applyTermGlossary(long videoId) {
        int updated = 0;
        for (TranscriptSegment segment : transcripts.listByVideoId(videoId)) {
            String original = segment.content();
            String repaired = termGlossary.apply(original);
            if (!repaired.equals(original)) {
                updated += transcripts.updateContentById(segment.id(), repaired);
            }
        }
        return updated;
    }

    public OcrSubtitleQualityResponse evaluateBurnedSubtitleOcr(long videoId) {
        requireVideo(videoId);
        return burnedSubtitleOcr.evaluate(videoId, transcripts.listByVideoId(videoId), 24);
    }

    public OcrSubtitleQualityResponse fuseBurnedSubtitleOcr(long videoId) {
        requireVideo(videoId);
        BurnedSubtitleOcrService.FusionResult result = burnedSubtitleOcr.fuse(videoId, transcripts.listByVideoId(videoId), 64);
        return applyOcrFusion(videoId, result);
    }

    public OcrSubtitleQualityResponse alignBurnedSubtitleOcr(long videoId) {
        requireVideo(videoId);
        BurnedSubtitleOcrService.FusionResult result = burnedSubtitleOcr.align(videoId, transcripts.listByVideoId(videoId), 512);
        return applyOcrFusion(videoId, result);
    }

    public OcrSubtitleQualityResponse refineLowConfidenceSubtitles(long videoId) {
        requireVideo(videoId);
        BurnedSubtitleOcrService.FusionResult result = burnedSubtitleOcr.refineLowConfidence(videoId, transcripts.listByVideoId(videoId), 512);
        return applyOcrFusion(videoId, result);
    }

    private OcrSubtitleQualityResponse applyOcrFusion(long videoId, BurnedSubtitleOcrService.FusionResult result) {
        return applyOcrFusion(videoId, result, true);
    }

    private OcrSubtitleQualityResponse applyOcrFusion(
            long videoId,
            BurnedSubtitleOcrService.FusionResult result,
            boolean rebuildDerivedAssets
    ) {
        if (!result.replacements().isEmpty()) {
            saveTranscriptSnapshot(videoId, "OCR_FUSION", "Before " + result.quality().mode());
        }
        int updated = 0;
        for (BurnedSubtitleOcrService.FusedSegment replacement : result.replacements()) {
            updated += transcripts.updateContentBySegmentIndex(
                    videoId,
                    replacement.segmentIndex(),
                    termGlossary.apply(replacement.text())
            );
        }
        updated += normalizeTermsWithOcrEvidence(videoId, result.quality());
        updated += applyTermGlossary(videoId);
        if (updated > 0 && rebuildDerivedAssets) {
            rebuildDerivedAssetsFromTranscripts(videoId);
        }
        return burnedSubtitleOcr.withAppliedReplacementCount(result.quality(), updated);
    }

    private void rebuildDerivedAssetsFromTranscripts(long videoId) {
        summaries.deleteByVideoId(videoId);
        VideoAsset video = videos.findById(videoId).orElseThrow();
        ensureSummaryAssetsFromTranscripts(video, transcripts.listByVideoId(videoId));
        indexVideoTranscripts(videoId);
        evictAgentAnswerCache(videoId);
    }

    private void evictAgentAnswerCache(long videoId) {
        List<String> scopes = new ArrayList<>();
        scopes.add("video:" + videoId);
        scopes.add("kb:default");
        knowledgeBases.knowledgeBaseIdsByVideoId(videoId).stream()
                .map(id -> "kb:" + id)
                .forEach(scopes::add);
        answerCache.evictScopes(scopes);
    }

    private int normalizeTermsWithOcrEvidence(long videoId, OcrSubtitleQualityResponse quality) {
        String evidence = quality.samples().stream()
                .map(sample -> sample.ocrText() == null ? "" : sample.ocrText().toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.joining(" "));
        boolean hasAgent = evidence.contains("agent");
        boolean hasSkill = evidence.contains("skill") || evidence.contains("skil");
        boolean hasClaudeCode = evidence.contains("claudecode") || evidence.contains("claude code");
        if (!hasAgent && !hasSkill && !hasClaudeCode) {
            return 0;
        }

        int updated = 0;
        for (TranscriptSegment segment : transcripts.listByVideoId(videoId)) {
            String text = segment.content();
            String normalized = normalizeTermText(text, hasAgent, hasSkill, hasClaudeCode);
            if (!normalized.equals(text)) {
                updated += transcripts.updateContentById(segment.id(), normalized);
            }
        }
        return updated;
    }

    private String normalizeTermText(String text, boolean hasAgent, boolean hasSkill, boolean hasClaudeCode) {
        String normalized = text;
        if (hasClaudeCode) {
            normalized = normalized.replaceAll("(?i)Code\\s*code", "Claude Code")
                    .replaceAll("(?i)Claude\\s*Code", "Claude Code");
        }
        if (hasAgent) {
            normalized = normalized.replace("A阵", "agent")
                    .replace("AZN", "agent")
                    .replace("A站", "agent");
        }
        if (hasSkill) {
            normalized = normalized.replaceAll("(?i)\\bskyo\\b", "skill")
                    .replaceAll("(?i)\\bskil\\b", "skill")
                    .replaceAll("(?i)Skills\\b", "skills");
        }
        return normalized;
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

    public List<TranscriptVersionResponse> transcriptVersions(long videoId) {
        requireVideo(videoId);
        return transcriptVersions.listByVideoId(videoId).stream()
                .map(this::toVersionResponse)
                .toList();
    }

    public TranscriptVersionDetailResponse transcriptVersionDetail(long videoId, long versionId) {
        requireVideo(videoId);
        TranscriptVersion version = transcriptVersions.findById(videoId, versionId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Transcript version not found"));
        List<TranscriptSnapshotSegment> snapshot = readTranscriptSnapshot(version.snapshotJson());
        Map<Integer, String> currentByIndex = transcripts.listByVideoId(videoId).stream()
                .collect(java.util.stream.Collectors.toMap(
                        TranscriptSegment::segmentIndex,
                        TranscriptSegment::content,
                        (left, right) -> left
                ));
        List<TranscriptVersionSegmentResponse> rows = snapshot.stream()
                .limit(120)
                .map(segment -> {
                    String currentContent = currentByIndex.getOrDefault(segment.segmentIndex(), "");
                    return new TranscriptVersionSegmentResponse(
                            segment.segmentIndex(),
                            segment.startMs(),
                            segment.endMs(),
                            segment.speaker(),
                            segment.content(),
                            currentContent,
                            !segment.content().equals(currentContent)
                    );
                })
                .toList();
        int changedCount = (int) snapshot.stream()
                .filter(segment -> !segment.content().equals(currentByIndex.getOrDefault(segment.segmentIndex(), "")))
                .count();
        return new TranscriptVersionDetailResponse(
                version.id(),
                version.videoId(),
                version.versionNo(),
                version.source(),
                version.note(),
                snapshot.size(),
                changedCount,
                version.createdAt(),
                rows
        );
    }

    public VideoDetailResponse editTranscriptSegment(long videoId, long segmentId, TranscriptEditRequest request) {
        requireVideo(videoId);
        TranscriptSegment segment = transcripts.findByVideoIdAndId(videoId, segmentId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Transcript segment not found"));
        String nextContent = termGlossary.apply(request.content());
        if (nextContent.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Transcript content cannot be blank");
        }
        if (nextContent.equals(segment.content())) {
            return detail(videoId);
        }

        saveTranscriptSnapshot(videoId, "MANUAL_EDIT", "Before editing segment #" + segment.segmentIndex());
        int updated = transcripts.updateContentByVideoIdAndId(videoId, segmentId, nextContent);
        if (updated == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "Transcript segment update was not applied");
        }
        rebuildDerivedAssetsFromTranscripts(videoId);
        videos.touchContentVersion(videoId);
        return detail(videoId);
    }

    public VideoDetailResponse restoreTranscriptVersion(long videoId, long versionId) {
        requireVideo(videoId);
        TranscriptVersion version = transcriptVersions.findById(videoId, versionId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Transcript version not found"));
        List<TranscriptSnapshotSegment> snapshot = readTranscriptSnapshot(version.snapshotJson());
        if (snapshot.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "Transcript version snapshot is empty");
        }

        saveTranscriptSnapshot(videoId, "RESTORE_BACKUP", "Before restoring version v" + version.versionNo());
        transcripts.replaceByVideoId(videoId, snapshot.stream().map(TranscriptSnapshotSegment::toDraft).toList());
        rebuildDerivedAssetsFromTranscripts(videoId);
        videos.touchContentVersion(videoId);
        return detail(videoId);
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
        seedProcessingResult(video, job, finalStep, storedFile, audioResult, asrResult, false);
    }

    private void seedProcessingResult(
            VideoAsset video,
            ProcessingJob job,
            String finalStep,
            StoredVideoFile storedFile,
            AudioExtractionResult audioResult,
            AsrTranscriptionResult asrResult,
            boolean replaceExistingTranscripts
    ) {
        boolean transcriptsTouched = false;
        if (replaceExistingTranscripts) {
            transcripts.replaceByVideoId(video.id(), buildTranscript(video, storedFile, audioResult, asrResult));
            summaries.deleteByVideoId(video.id());
            transcriptsTouched = true;
        } else if (!transcripts.existsByVideoId(video.id())) {
            transcripts.insertBatch(video.id(), buildTranscript(video, storedFile, audioResult, asrResult));
            transcriptsTouched = true;
        }

        if (transcriptsTouched && hasRealAsrSegments(asrResult)) {
            applyDefaultOcrFusion(video.id(), storedFile, asrResult);
            rebuildDerivedAssetsFromTranscripts(video.id());
        } else {
            indexVideoTranscripts(video.id());
            ensureSummaryAssetsFromAsr(video, asrResult);
        }

        ProcessingJob current = jobs.findById(job.id()).orElseThrow();
        jobs.advance(current.id(), current.version(), finalStep, 100, "DONE");
        cacheProgress(video.id(), jobs.findById(job.id()).orElseThrow());
        videos.markReady(video.id());
    }

    private boolean hasRealAsrSegments(AsrTranscriptionResult asrResult) {
        return asrResult != null && !asrResult.segments().isEmpty();
    }

    private void applyDefaultOcrFusion(long videoId, StoredVideoFile storedFile, AsrTranscriptionResult asrResult) {
        if (!ocrAutoFusionEnabled || storedFile == null || !hasRealAsrSegments(asrResult)) {
            return;
        }
        try {
            BurnedSubtitleOcrService.FusionResult result = switch (ocrAutoFusionMode) {
                case "align", "strong", "strong-visual" ->
                        burnedSubtitleOcr.align(videoId, transcripts.listByVideoId(videoId), 512);
                case "refine", "low-confidence" ->
                        burnedSubtitleOcr.refineLowConfidence(videoId, transcripts.listByVideoId(videoId), 512);
                default ->
                        burnedSubtitleOcr.fuse(videoId, transcripts.listByVideoId(videoId), 64);
            };
            OcrSubtitleQualityResponse applied = applyOcrFusion(videoId, result, false);
            log.info(
                    "Default ASR+OCR fusion finished for video {} mode={} available={} applied={}",
                    videoId,
                    applied.mode(),
                    applied.ocrAvailable(),
                    applied.appliedReplacementCount()
            );
        } catch (RuntimeException exception) {
            log.warn("Default ASR+OCR fusion skipped for video {}: {}", videoId, exception.getMessage());
        }
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
            String content = termGlossary.apply(segment.text());
            drafts.add(new TranscriptDraft(
                    index,
                    segment.startMs(),
                    segment.endMs(),
                    "ASR",
                    content,
                    tokenCount(content)
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
                .map(segment -> new SummarySourceSegment(
                        segment.startMs(),
                        segment.endMs(),
                        termGlossary.apply(segment.text())
                ))
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

    private void saveTranscriptSnapshot(long videoId, String source, String note) {
        List<TranscriptSegment> current = transcripts.listByVideoId(videoId);
        if (current.isEmpty()) {
            return;
        }
        try {
            List<TranscriptSnapshotSegment> snapshot = current.stream()
                    .map(segment -> new TranscriptSnapshotSegment(
                            segment.segmentIndex(),
                            segment.startMs(),
                            segment.endMs(),
                            segment.speaker(),
                            segment.content(),
                            segment.tokenCount()
                    ))
                    .toList();
            transcriptVersions.insert(
                    videoId,
                    source,
                    note == null ? "" : note.substring(0, Math.min(note.length(), 255)),
                    objectMapper.writeValueAsString(snapshot)
            );
        } catch (JsonProcessingException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to save transcript version");
        }
    }

    private List<TranscriptSnapshotSegment> readTranscriptSnapshot(String snapshotJson) {
        try {
            return objectMapper.readValue(snapshotJson, new TypeReference<List<TranscriptSnapshotSegment>>() {
            });
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.CONFLICT, "Transcript version snapshot is invalid");
        }
    }

    private TranscriptVersionResponse toVersionResponse(TranscriptVersion version) {
        List<TranscriptSnapshotSegment> snapshot = readTranscriptSnapshot(version.snapshotJson());
        String preview = snapshot.stream()
                .map(TranscriptSnapshotSegment::content)
                .filter(text -> text != null && !text.isBlank())
                .findFirst()
                .map(text -> text.length() <= 90 ? text : text.substring(0, 89) + "...")
                .orElse("");
        return new TranscriptVersionResponse(
                version.id(),
                version.videoId(),
                version.versionNo(),
                version.source(),
                version.note(),
                snapshot.size(),
                preview,
                version.createdAt()
        );
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
