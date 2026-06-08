package com.omnivid.api.agent;

import com.omnivid.api.common.ApiException;
import com.omnivid.api.agent.cache.AgentAnswerCache;
import com.omnivid.api.agent.memory.AgentShortTermMemory;
import com.omnivid.api.agent.retrieval.TranscriptVectorSearch;
import com.omnivid.api.ratelimit.AgentRateLimiter;
import com.omnivid.api.llm.CloudLlmClient;
import com.omnivid.api.llm.CloudLlmResult;
import com.omnivid.api.transcript.TranscriptRepository;
import com.omnivid.api.transcript.TranscriptSegment;
import com.omnivid.api.video.VideoAsset;
import com.omnivid.api.video.VideoService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class AgentService {
    private static final long DEMO_USER_ID = 1L;
    private static final int TOP_K = 3;
    private static final int CONTEXT_MESSAGE_LIMIT = 6;
    private static final double SEMANTIC_EVIDENCE_THRESHOLD = 0.32;
    private static final double CITATION_SEMANTIC_THRESHOLD = 0.72;
    private static final Set<String> QUERY_STOP_WORDS = Set.of(
            "the", "and", "for", "with", "where", "which", "what", "when", "who", "how",
            "clips", "clip", "video", "videos", "mention", "mentioned", "mentions", "knowledge", "base",
            "please", "cite", "show", "tell", "about", "from", "that", "this", "are", "is", "in", "on", "of", "to"
    );

    private final VideoService videos;
    private final TranscriptRepository transcripts;
    private final ChatMessageRepository chatMessages;
    private final AgentRateLimiter rateLimiter;
    private final AgentAnswerCache answerCache;
    private final AgentShortTermMemory shortTermMemory;
    private final TranscriptVectorSearch vectorSearch;
    private final CloudLlmClient llm;

    public AgentService(
            VideoService videos,
            TranscriptRepository transcripts,
            ChatMessageRepository chatMessages,
            AgentRateLimiter rateLimiter,
            AgentAnswerCache answerCache,
            AgentShortTermMemory shortTermMemory,
            TranscriptVectorSearch vectorSearch,
            CloudLlmClient llm
    ) {
        this.videos = videos;
        this.transcripts = transcripts;
        this.chatMessages = chatMessages;
        this.rateLimiter = rateLimiter;
        this.answerCache = answerCache;
        this.shortTermMemory = shortTermMemory;
        this.vectorSearch = vectorSearch;
        this.llm = llm;
    }

    public AgentAskResponse ask(long videoId, AgentAskRequest request) {
        String scope = "video:" + videoId;
        requireAllowed(scope);
        videos.requireVideo(videoId);
        Optional<GuardrailDecision> guardrail = inspectQuestion(request.question());
        if (guardrail.isPresent()) {
            return blockedVideoResponse(videoId, request.question(), guardrail.get());
        }
        if (isGreeting(request.question())) {
            return greetingVideoResponse(videoId, request.question());
        }
        if (isAgentIntroQuestion(request.question())) {
            return agentIntroVideoResponse(videoId, request.question());
        }
        boolean contextualFollowUp = isContextualFollowUp(request.question());
        if (!contextualFollowUp) {
            Optional<AgentAskResponse> cached = answerCache.get(scope, request.question());
            if (cached.isPresent()) {
                return cached.get();
            }
        }
        Optional<String> shortTermQuestion = Optional.empty();
        Optional<String> historyQuestion = Optional.empty();
        if (contextualFollowUp) {
            List<ChatMessage> recentMessages = chatMessages.listRecentByVideoId(videoId, CONTEXT_MESSAGE_LIMIT);
            shortTermQuestion = shortTermMemory.getLastQuestion(videoId);
            historyQuestion = shortTermQuestion.isPresent()
                    ? Optional.empty()
                    : lastUserQuestion(recentMessages);
        }
        Optional<String> previousQuestion = shortTermQuestion.isPresent() ? shortTermQuestion : historyQuestion;
        List<TranscriptSegment> segments = transcripts.listByVideoId(videoId);
        List<Evidence> evidenceList = selectEvidence(segments, request.question());
        Evidence evidence = evidenceList.isEmpty() ? Evidence.empty() : evidenceList.getFirst();
        List<AgentCitation> citations = buildCitations(evidenceList, List.of());
        String citation = citations.isEmpty() ? "" : citations.getFirst().citation();
        GeneratedAnswer generatedAnswer = generateVideoAnswer(request.question(), evidence, citations, previousQuestion);
        String answer = generatedAnswer.answer();
        String confidenceLevel = confidenceLevel(evidence);
        List<AgentTraceStep> trace = buildVideoTrace(
                contextualFollowUp,
                shortTermQuestion,
                historyQuestion,
                segments.size(),
                evidenceList.size(),
                citations.size(),
                evidence.score(),
                evidence.vectorScore(),
                evidence.keywordScore(),
                confidenceLevel,
                generatedAnswer
        );
        chatMessages.insert(videoId, "user", request.question(), null);
        chatMessages.insert(videoId, "assistant", answer, citation.isBlank() ? null : citation);
        shortTermMemory.rememberLastQuestion(videoId, request.question());
        String answerMode = answerMode(citations, generatedAnswer, false);
        AgentAskResponse response = new AgentAskResponse(
                answer,
                citation,
                videoId,
                evidence.hit() == null ? 0 : evidence.hit().startMs(),
                evidence.hit() == null ? 0 : evidence.hit().endMs(),
                citations,
                evidence.score(),
                confidenceLevel,
                previousQuestion.isPresent(),
                false,
                answerMode,
                trace
        );
        if (!contextualFollowUp) {
            answerCache.put(scope, request.question(), response);
        }
        return response;
    }

    public List<ChatMessage> messages(long videoId) {
        videos.requireVideo(videoId);
        return chatMessages.listRecentByVideoId(videoId, 20);
    }

    public AgentContextResponse context(long videoId) {
        videos.requireVideo(videoId);
        List<ChatMessage> recentMessages = chatMessages.listRecentByVideoId(videoId, CONTEXT_MESSAGE_LIMIT);
        Optional<String> shortTermQuestion = shortTermMemory.getLastQuestion(videoId);
        Optional<String> lastQuestion = lastUserQuestion(recentMessages);
        return new AgentContextResponse(
                videoId,
                CONTEXT_MESSAGE_LIMIT,
                recentMessages.size(),
                shortTermQuestion.isPresent() || lastQuestion.isPresent(),
                lastQuestion.orElse(""),
                shortTermQuestion.orElse(""),
                shortTermMemory.source(),
                recentMessages
        );
    }

    public int clearMessages(long videoId) {
        videos.requireVideo(videoId);
        shortTermMemory.clear(videoId);
        return chatMessages.deleteByVideoId(videoId);
    }

    public AgentAskResponse askDefaultKnowledgeBase(AgentAskRequest request) {
        String scope = "kb:default";
        requireAllowed(scope);
        Optional<GuardrailDecision> guardrail = inspectQuestion(request.question());
        if (guardrail.isPresent()) {
            return blockedKnowledgeBaseResponse(guardrail.get());
        }
        if (isGreeting(request.question())) {
            return greetingKnowledgeBaseResponse();
        }
        if (isAgentIntroQuestion(request.question())) {
            return agentIntroKnowledgeBaseResponse();
        }
        Optional<AgentAskResponse> cached = answerCache.get(scope, request.question());
        if (cached.isPresent()) {
            return cached.get();
        }
        List<VideoAsset> videoAssets = videos.listVideos();
        List<Long> videoIds = videoAssets.stream().map(VideoAsset::id).toList();
        List<TranscriptSegment> segments = transcripts.listByVideoIds(videoIds);
        List<Evidence> evidenceList = selectEvidence(segments, request.question());
        Evidence evidence = evidenceList.isEmpty() ? Evidence.empty() : evidenceList.getFirst();
        List<AgentCitation> citations = buildCitations(evidenceList, videoAssets);
        String citation = citations.isEmpty() ? "" : citations.getFirst().citation();
        GeneratedAnswer generatedAnswer = generateKnowledgeBaseAnswer(request.question(), evidence, citations, videoAssets.size());
        String answer = generatedAnswer.answer();
        long citedVideoId = evidence.hit() == null ? 0 : evidence.hit().videoId();
        String confidenceLevel = confidenceLevel(evidence);
        List<AgentTraceStep> trace = buildKnowledgeBaseTrace(
                videoAssets.size(),
                segments.size(),
                evidenceList.size(),
                citations.size(),
                evidence.score(),
                evidence.vectorScore(),
                evidence.keywordScore(),
                confidenceLevel,
                citedVideoId > 0,
                generatedAnswer
        );
        if (citedVideoId > 0) {
            chatMessages.insert(citedVideoId, "user", request.question(), null);
            chatMessages.insert(citedVideoId, "assistant", answer, citation.isBlank() ? null : citation);
        }
        String answerMode = answerMode(citations, generatedAnswer, true);
        AgentAskResponse response = new AgentAskResponse(
                answer,
                citation,
                citedVideoId,
                evidence.hit() == null ? 0 : evidence.hit().startMs(),
                evidence.hit() == null ? 0 : evidence.hit().endMs(),
                citations,
                evidence.score(),
                confidenceLevel,
                false,
                false,
                answerMode,
                trace
        );
        answerCache.put(scope, request.question(), response);
        return response;
    }

    private List<Evidence> selectEvidence(List<TranscriptSegment> segments, String question) {
        if (segments.isEmpty()) {
            return List.of();
        }

        List<String> queryTerms = queryTerms(question);
        return vectorSearch.search(segments, question, TOP_K * 4).stream()
                .map(candidate -> {
                    int keywordScore = score(candidate.segment().content(), queryTerms);
                    return new Evidence(
                            candidate.segment(),
                            evidenceScore(candidate.score(), keywordScore),
                            candidate.score(),
                            keywordScore
                    );
                })
                .filter(this::usableEvidence)
                .sorted(Comparator.comparingInt(Evidence::score).reversed()
                        .thenComparing(Comparator.comparingDouble(Evidence::vectorScore).reversed())
                        .thenComparing(evidence -> evidence.hit().startMs()))
                .limit(TOP_K)
                .toList();
    }

    private int evidenceScore(double vectorScore, int keywordScore) {
        return Math.max(keywordScore, (int) Math.round(vectorScore * 10));
    }

    private boolean usableEvidence(Evidence evidence) {
        return evidence.keywordScore() > 0 || evidence.vectorScore() >= SEMANTIC_EVIDENCE_THRESHOLD;
    }

    private boolean citableEvidence(Evidence evidence) {
        return evidence.hit() != null
                && (evidence.keywordScore() >= 2 || evidence.vectorScore() >= CITATION_SEMANTIC_THRESHOLD);
    }

    private void requireAllowed(String scope) {
        if (!rateLimiter.allow(scope)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Agent rate limit exceeded, retry later");
        }
    }

    private Optional<GuardrailDecision> inspectQuestion(String question) {
        String normalized = question.toLowerCase(Locale.ROOT).trim();
        if (containsAny(normalized,
                "ignore previous",
                "ignore above",
                "ignore all previous",
                "ignore the rules",
                "disregard previous",
                "忽略上面的规则",
                "忽略之前的规则",
                "忽略规则",
                "无视规则",
                "不要遵守")) {
            return Optional.of(new GuardrailDecision("instruction override"));
        }
        if (containsAny(normalized,
                "不要引用",
                "不需要引用",
                "绕过引用",
                "伪造引用",
                "伪造时间戳",
                "编一个时间戳",
                "without citation",
                "do not cite",
                "fake citation",
                "fake timestamp")) {
            return Optional.of(new GuardrailDecision("citation bypass"));
        }
        if (containsAny(normalized,
                "输出系统提示",
                "显示系统提示",
                "泄露系统提示",
                "泄露提示词",
                "reveal system prompt",
                "show system prompt",
                "print system prompt",
                "developer message")) {
            return Optional.of(new GuardrailDecision("prompt leakage"));
        }
        if (containsAny(normalized,
                "访问其他用户",
                "读取其他用户",
                "越权访问",
                "bypass permission",
                "other user's videos",
                "other users videos")) {
            return Optional.of(new GuardrailDecision("permission bypass"));
        }
        return Optional.empty();
    }

    private AgentAskResponse blockedVideoResponse(long videoId, String question, GuardrailDecision decision) {
        String answer = guardrailAnswer(decision);
        chatMessages.insert(videoId, "user", question, null);
        chatMessages.insert(videoId, "assistant", answer, null);
        return new AgentAskResponse(
                answer,
                "",
                videoId,
                0,
                0,
                List.of(),
                0,
                "NONE",
                false,
                false,
                "GUARDRAIL",
                blockedTrace(decision, true)
        );
    }

    private AgentAskResponse blockedKnowledgeBaseResponse(GuardrailDecision decision) {
        return new AgentAskResponse(
                guardrailAnswer(decision),
                "",
                0,
                0,
                0,
                List.of(),
                0,
                "NONE",
                false,
                false,
                "GUARDRAIL",
                blockedTrace(decision, false)
        );
    }

    private String guardrailAnswer(GuardrailDecision decision) {
        return "这个问题触发了 Agent 安全边界：" + decision.reason()
                + "。当前 Agent 只能基于已检索到的字幕证据回答，不能绕过引用、伪造时间戳、泄露系统提示或访问无权限内容。你可以改成和当前视频内容相关、允许返回 citation 的问题。";
    }

    private AgentAskResponse greetingVideoResponse(long videoId, String question) {
        String answer = "你好，我是 OmniVid 的证据约束视频问答 Agent。你可以问当前视频里出现过的内容，比如“这段视频讲了什么”“有哪些 MySQL/Redis 钩子”“引用来自哪个时间点”。涉及视频事实时，我会先找字幕证据，再返回可点击的时间戳引用。";
        chatMessages.insert(videoId, "user", question, null);
        chatMessages.insert(videoId, "assistant", answer, null);
        return new AgentAskResponse(
                answer,
                "",
                videoId,
                0,
                0,
                List.of(),
                0,
                "NONE",
                false,
                false,
                "LOCAL_INTENT",
                greetingTrace(true)
        );
    }

    private AgentAskResponse greetingKnowledgeBaseResponse() {
        String answer = "你好，我是 OmniVid 的默认知识库 Agent。你可以问已上传视频里共同提到的主题、某个技术点在哪些视频出现过，或者让帮你整理可追溯的面试钩子。涉及视频事实时，我会先检索字幕证据，再返回来源视频和时间戳。";
        return new AgentAskResponse(
                answer,
                "",
                0,
                0,
                0,
                List.of(),
                0,
                "NONE",
                false,
                false,
                "LOCAL_INTENT",
                greetingTrace(false)
        );
    }

    private AgentAskResponse agentIntroVideoResponse(long videoId, String question) {
        String answer = "我是 OmniVid 的视频语义问答 Agent，主要负责把当前视频的 ASR 字幕、总结和时间轴引用串起来。你可以问我三类问题：第一，当前视频讲了什么；第二，某个 Java 后端或 AI Agent 面试钩子在视频哪个时间点出现；第三，围绕已命中的字幕证据继续追问。涉及视频事实时，我会先检索字幕证据，命中后调用已激活的云端 LLM 解释这段视频并返回可追溯时间戳；没有命中时，我会先说明视频里没有检索到相关内容，再用云端 LLM 给出通用回答。像自我介绍这类元问题不需要视频证据，所以我会直接回答，不消耗 LLM token。";
        chatMessages.insert(videoId, "user", question, null);
        chatMessages.insert(videoId, "assistant", answer, null);
        return new AgentAskResponse(
                answer,
                "",
                videoId,
                0,
                0,
                List.of(),
                0,
                "NONE",
                false,
                false,
                "LOCAL_INTENT",
                agentIntroTrace(true)
        );
    }

    private AgentAskResponse agentIntroKnowledgeBaseResponse() {
        String answer = "我是 OmniVid 的默认知识库问答 Agent，负责在所有已上传视频的字幕里做跨视频检索，并把回答绑定到来源视频和时间戳。你可以让我找某个技术点在哪些视频里出现过，也可以让我把 MySQL、Redis、并发、JVM、Spring、MQ、RAG 等面试钩子整理成可追溯回答。涉及视频事实时，我会先拿到 citation，再调用已激活的云端 LLM 做表达整理；没有证据时会先说明知识库视频未提到，再用云端 LLM 给出通用回答。";
        return new AgentAskResponse(
                answer,
                "",
                0,
                0,
                0,
                List.of(),
                0,
                "NONE",
                false,
                false,
                "LOCAL_INTENT",
                agentIntroTrace(false)
        );
    }

    private String buildAnswer(String question, Evidence evidence, Optional<String> previousQuestion) {
        String contextPrefix = previousQuestion
                .map(previous -> "结合上一轮你问到的「" + compact(previous, 48) + "」，")
                .orElse("");
        if (question.contains("你是谁")) {
            return contextPrefix + "我是 OmniVid 的证据约束视频问答 Agent。现在会先检索当前视频的真实 ASR 字幕，再基于时间戳引用回答；云端 LLM 可配置启用，但回答仍必须绑定字幕证据。";
        }
        if (evidence.hit() == null) {
            return noVideoEvidencePrefix(question, previousQuestion)
                    + "云端 LLM 当前未启用或调用失败，所以这次只能先返回视频证据状态。请在左侧云端 LLM 面板启用可用 Provider 后重试。";
        }
        return contextPrefix + "根据当前视频的 ASR 字幕，最相关证据是：" + evidence.hit().content()
                + " 这个回答来自时间戳片段 "
                + formatTime(evidence.hit().startMs()) + "-" + formatTime(evidence.hit().endMs())
                + "。云端 LLM 未启用或调用失败时，我会用本地兜底模板保证引用可追溯。";
    }

    private Optional<String> lastUserQuestion(List<ChatMessage> messages) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            ChatMessage message = messages.get(index);
            if ("user".equals(message.role())) {
                return Optional.of(message.content());
            }
        }
        return Optional.empty();
    }

    private String buildKnowledgeBaseAnswer(String question, Evidence evidence, int videoCount) {
        if (question.contains("你是谁")) {
            return "我是 OmniVid 的知识库检索 Agent。现在会在默认知识库的所有已上传视频字幕里找证据，云端 LLM 可配置启用，但不会脱离字幕引用直接编答案。";
        }
        if (evidence.hit() == null) {
            return noKnowledgeBaseEvidencePrefix(question, videoCount)
                    + "云端 LLM 当前未启用或调用失败，所以这次只能先返回知识库证据状态。请在左侧云端 LLM 面板启用可用 Provider 后重试。";
        }
        return "我在默认知识库的 " + videoCount + " 个视频里找到了最相关证据：" + evidence.hit().content()
                + " 引用时间戳为 "
                + formatTime(evidence.hit().startMs()) + "-" + formatTime(evidence.hit().endMs())
                + "。";
    }

    private GeneratedAnswer generateVideoAnswer(
            String question,
            Evidence evidence,
            List<AgentCitation> citations,
            Optional<String> previousQuestion
    ) {
        String localAnswer = buildAnswer(question, evidence, previousQuestion);
        if (question.contains("你是谁")) {
            return GeneratedAnswer.local(localAnswer);
        }
        if (evidence.hit() == null || citations.isEmpty()) {
            String systemPrompt = """
                    你是 OmniVid 的通用补充问答 Agent。
                    当前问题没有命中视频字幕证据，你可以基于通用知识回答，但不能声称这些内容来自当前视频。
                    如果问题涉及实时信息、医疗、法律、金融等高风险场景，需要提示不确定性并建议用户核验。
                    用中文回答，表达自然、具体、简洁。
                    """;
            String userPrompt = """
                    当前视频字幕没有检索到与问题直接相关的片段。
                    问题：%s
                    上一轮问题：%s

                    请给出通用回答，不要编造视频时间戳，不要说“视频中提到”。
                    """.formatted(question, previousQuestion.orElse("无"));

            return llm.complete(systemPrompt, userPrompt, 900)
                    .map(result -> prefixedCloudAnswer(result, noVideoEvidencePrefix(question, previousQuestion)))
                    .orElseGet(() -> GeneratedAnswer.local(localAnswer));
        }

        String systemPrompt = """
                你是 OmniVid 的证据约束视频问答 Agent。
                你只能基于用户提供的 citations / ASR 字幕证据回答，不能编造字幕外事实。
                不能生成新的时间戳，不能伪造 citation，只能复用 citations 里出现的时间戳和片段。
                如果证据不足，直接说明证据不足，并建议用户换关键词或等待向量检索增强。
                """;
        String userPrompt = """
                问题：%s
                上一轮问题：%s

                可用 citations：
                %s

                请用中文回答，2 到 5 句。回答要自然，但必须围绕上面的证据。
                """.formatted(question, previousQuestion.orElse("无"), citationEvidenceBlock(citations));

        return llm.complete(systemPrompt, userPrompt, 900)
                .map(this::cloudAnswer)
                .orElseGet(() -> GeneratedAnswer.local(localAnswer));
    }

    private GeneratedAnswer generateKnowledgeBaseAnswer(
            String question,
            Evidence evidence,
            List<AgentCitation> citations,
            int videoCount
    ) {
        String localAnswer = buildKnowledgeBaseAnswer(question, evidence, videoCount);
        if (question.contains("你是谁")) {
            return GeneratedAnswer.local(localAnswer);
        }
        if (evidence.hit() == null || citations.isEmpty()) {
            String systemPrompt = """
                    你是 OmniVid 的默认知识库通用补充问答 Agent。
                    当前问题没有命中任何已上传视频字幕证据，你可以基于通用知识回答，但不能声称这些内容来自知识库视频。
                    如果问题涉及实时信息、医疗、法律、金融等高风险场景，需要提示不确定性并建议用户核验。
                    用中文回答，表达自然、具体、简洁。
                    """;
            String userPrompt = """
                    默认知识库视频数：%d
                    问题：%s

                    请给出通用回答，不要编造来源视频或时间戳，不要说“知识库视频中提到”。
                    """.formatted(videoCount, question);

            return llm.complete(systemPrompt, userPrompt, 900)
                    .map(result -> prefixedCloudAnswer(result, noKnowledgeBaseEvidencePrefix(question, videoCount)))
                    .orElseGet(() -> GeneratedAnswer.local(localAnswer));
        }

        String systemPrompt = """
                你是 OmniVid 的默认知识库问答 Agent。
                你只能基于用户提供的跨视频 citations / ASR 字幕证据回答，不能编造视频外事实。
                不能生成新的时间戳，不能伪造 citation，只能复用 citations 里出现的来源、时间戳和片段。
                如果证据不足，直接说明证据不足。
                """;
        String userPrompt = """
                默认知识库视频数：%d
                问题：%s

                可用 citations：
                %s

                请用中文回答，2 到 5 句。需要说明答案来自这些视频证据，不要输出 citations 外的新来源。
                """.formatted(videoCount, question, citationEvidenceBlock(citations));

        return llm.complete(systemPrompt, userPrompt, 900)
                .map(this::cloudAnswer)
                .orElseGet(() -> GeneratedAnswer.local(localAnswer));
    }

    private List<String> queryTerms(String question) {
        String normalized = question.toLowerCase(Locale.ROOT);
        List<String> terms = new ArrayList<>();
        for (String raw : normalized.split("[^a-z0-9\\u4e00-\\u9fa5]+")) {
            if (raw.length() >= 2 && !QUERY_STOP_WORDS.contains(raw)) {
                terms.add(raw);
            }
        }
        addSynonyms(normalized, terms);
        return terms;
    }

    private void addSynonyms(String question, List<String> terms) {
        if (question.contains("mysql") || question.contains("my sql") || question.contains("数据库")) {
            terms.addAll(List.of("mysql", "my sql", "sql", "database", "status"));
        }
        if (question.contains("redis") || question.contains("缓存") || question.contains("进度")) {
            terms.addAll(List.of("redis", "readies", "cache", "progress"));
        }
        if (question.contains("asr") || question.contains("字幕") || question.contains("语音")) {
            terms.addAll(List.of("asr", "subtitle", "audio", "speech"));
        }
        if (question.contains("上传") || question.contains("文件")) {
            terms.addAll(List.of("upload", "file", "video"));
        }
        if (question.contains("agent") || question.contains("rag") || question.contains("问答")) {
            terms.addAll(List.of("agent", "rag", "answer", "question"));
        }
    }

    private boolean isGreeting(String question) {
        String normalized = question.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\p{Punct}，。！？、：；“”‘’（）【】《》]+", "");
        if (normalized.length() > 10) {
            return false;
        }
        return List.of(
                "你好",
                "您好",
                "你好呀",
                "你好啊",
                "hello",
                "hi",
                "嗨",
                "哈喽",
                "在吗",
                "在不在"
        ).contains(normalized);
    }

    private boolean isAgentIntroQuestion(String question) {
        String normalized = question.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\p{Punct}，。！？、：；“”‘’（）【】《》]+", "");
        return containsAny(normalized,
                "介绍一下你自己",
                "介绍你自己",
                "你是谁",
                "你是什么",
                "你叫什么",
                "你能做什么",
                "你有什么功能",
                "你会做什么",
                "你能干什么",
                "能干什么",
                "有什么功能",
                "whoareyou",
                "whatcanyoudo",
                "help");
    }

    private boolean isContextualFollowUp(String question) {
        String normalized = question.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\p{Punct}，。！？、：；“”‘’（）【】《》]+", "");
        if (containsAny(normalized, "上一轮", "上一个", "刚才", "前面", "上面", "前一个")) {
            return true;
        }
        if (normalized.startsWith("继续")
                || normalized.startsWith("接着")
                || normalized.startsWith("展开")
                || normalized.startsWith("详细说")
                || normalized.startsWith("详细解释")
                || normalized.startsWith("再说说")) {
            return true;
        }
        if (normalized.length() <= 12 && !normalized.contains("视频")
                && containsAny(normalized, "这个", "那个", "它", "他", "呢", "为什么")) {
            return true;
        }
        return List.of(
                "continue",
                "goon",
                "elaborate",
                "tellmemore",
                "more",
                "why",
                "whyisthat"
        ).contains(normalized);
    }

    private int score(String content, List<String> terms) {
        String normalized = content.toLowerCase(Locale.ROOT);
        int score = 0;
        for (String term : terms) {
            if (normalized.contains(term)) {
                score += term.length() >= 5 ? 2 : 1;
            }
        }
        return score;
    }

    private String confidenceLevel(Evidence evidence) {
        if (evidence.hit() == null || evidence.score() <= 0) {
            return "NONE";
        }
        if (evidence.score() >= 4) {
            return "HIGH";
        }
        if (evidence.score() >= 2) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private List<AgentTraceStep> buildVideoTrace(
            boolean memoryChecked,
            Optional<String> shortTermQuestion,
            Optional<String> historyQuestion,
            int segmentCount,
            int evidenceCount,
            int citationCount,
            int confidenceScore,
            double vectorScore,
            int keywordScore,
            String confidenceLevel,
            GeneratedAnswer generatedAnswer
    ) {
        List<AgentTraceStep> trace = new ArrayList<>();
        trace.add(new AgentTraceStep(
                "InputGuardrail",
                "done",
                "question passed injection checks"
        ));
        trace.add(new AgentTraceStep(
                "MemoryTool",
                memoryStatus(memoryChecked, shortTermQuestion, historyQuestion),
                memoryDetail(memoryChecked, shortTermQuestion, historyQuestion)
        ));
        trace.add(new AgentTraceStep(
                "TranscriptRetrieveTool",
                segmentCount > 0 ? "done" : "miss",
                "segments=" + segmentCount
        ));
        trace.add(new AgentTraceStep(
                "VectorRetrieveTool",
                evidenceCount > 0 ? "done" : "miss",
                "provider=" + vectorSearch.providerName()
                        + ", index=" + vectorSearch.indexName()
                        + ", dimensions=" + vectorSearch.dimensions()
                        + ", candidates=" + evidenceCount
                        + ", topCosine=" + formatScore(vectorScore)
        ));
        trace.add(new AgentTraceStep(
                "RerankTool",
                evidenceCount > 0 ? "done" : "skip",
                "topK=" + Math.min(TOP_K, evidenceCount) + ", keywordScore=" + keywordScore
        ));
        trace.add(new AgentTraceStep(
                "CitationBuilderTool",
                citationCount > 0 ? "done" : "miss",
                "citations=" + citationCount + ", strictFilter=keyword>=2|cosine>=0.72"
        ));
        trace.add(new AgentTraceStep(
                "AnswerPolicyTool",
                citationCount > 0 ? "done" : "warn",
                citationCount > 0
                        ? "video evidence found, answer must cite timestamps"
                        : "no video evidence, answer should disclose miss then use general LLM"
        ));
        trace.add(new AgentTraceStep(
                "LlmGenerateTool",
                generatedAnswer.cloudUsed() ? "done" : "skip",
                generationTraceDetail(generatedAnswer)
        ));
        trace.add(new AgentTraceStep(
                "ConfidenceGuard",
                confidenceTraceStatus(confidenceLevel),
                "level=" + confidenceLevel + ", score=" + confidenceScore
        ));
        trace.add(new AgentTraceStep(
                "PersistTool",
                "done",
                "chat_message saved, short-term memory updated"
        ));
        return trace;
    }

    private List<AgentTraceStep> buildKnowledgeBaseTrace(
            int videoCount,
            int segmentCount,
            int evidenceCount,
            int citationCount,
            int confidenceScore,
            double vectorScore,
            int keywordScore,
            String confidenceLevel,
            boolean persisted,
            GeneratedAnswer generatedAnswer
    ) {
        List<AgentTraceStep> trace = new ArrayList<>();
        trace.add(new AgentTraceStep(
                "InputGuardrail",
                "done",
                "question passed injection checks"
        ));
        trace.add(new AgentTraceStep(
                "ScopeTool",
                videoCount > 0 ? "done" : "miss",
                "default knowledge base videos=" + videoCount
        ));
        trace.add(new AgentTraceStep(
                "TranscriptRetrieveTool",
                segmentCount > 0 ? "done" : "miss",
                "segments=" + segmentCount
        ));
        trace.add(new AgentTraceStep(
                "VectorRetrieveTool",
                evidenceCount > 0 ? "done" : "miss",
                "provider=" + vectorSearch.providerName()
                        + ", index=" + vectorSearch.indexName()
                        + ", dimensions=" + vectorSearch.dimensions()
                        + ", candidates=" + evidenceCount
                        + ", topCosine=" + formatScore(vectorScore)
        ));
        trace.add(new AgentTraceStep(
                "RerankTool",
                evidenceCount > 0 ? "done" : "skip",
                "topK=" + Math.min(TOP_K, evidenceCount) + ", keywordScore=" + keywordScore
        ));
        trace.add(new AgentTraceStep(
                "CitationBuilderTool",
                citationCount > 0 ? "done" : "miss",
                "citations=" + citationCount + ", strictFilter=keyword>=2|cosine>=0.72"
        ));
        trace.add(new AgentTraceStep(
                "AnswerPolicyTool",
                citationCount > 0 ? "done" : "warn",
                citationCount > 0
                        ? "knowledge-base evidence found, answer must cite source video"
                        : "no knowledge-base evidence, answer should disclose miss then use general LLM"
        ));
        trace.add(new AgentTraceStep(
                "LlmGenerateTool",
                generatedAnswer.cloudUsed() ? "done" : "skip",
                generationTraceDetail(generatedAnswer)
        ));
        trace.add(new AgentTraceStep(
                "ConfidenceGuard",
                confidenceTraceStatus(confidenceLevel),
                "level=" + confidenceLevel + ", score=" + confidenceScore
        ));
        trace.add(new AgentTraceStep(
                "PersistTool",
                persisted ? "done" : "skip",
                persisted ? "chat_message saved to cited video" : "no citation, skip chat_message write"
        ));
        return trace;
    }

    private List<AgentTraceStep> blockedTrace(GuardrailDecision decision, boolean persisted) {
        return List.of(
                new AgentTraceStep(
                        "InputGuardrail",
                        "blocked",
                        "reason=" + decision.reason()
                ),
                new AgentTraceStep(
                        "TranscriptRetrieveTool",
                        "skip",
                        "blocked before retrieval"
                ),
                new AgentTraceStep(
                        "CitationBuilderTool",
                        "skip",
                        "no citation generated"
                ),
                new AgentTraceStep(
                        "ConfidenceGuard",
                        "miss",
                        "level=NONE, score=0"
                ),
                new AgentTraceStep(
                        "PersistTool",
                        persisted ? "done" : "skip",
                        persisted ? "guardrail response saved, short-term memory skipped" : "knowledge base guardrail response not tied to a video"
                )
        );
    }

    private List<AgentTraceStep> greetingTrace(boolean persisted) {
        return List.of(
                new AgentTraceStep(
                        "InputGuardrail",
                        "done",
                        "question passed injection checks"
                ),
                new AgentTraceStep(
                        "GreetingIntent",
                        "done",
                        "small talk handled without transcript retrieval"
                ),
                new AgentTraceStep(
                        "TranscriptRetrieveTool",
                        "skip",
                        "greeting does not require video evidence"
                ),
                new AgentTraceStep(
                        "LlmGenerateTool",
                        "skip",
                        "local greeting response"
                ),
                new AgentTraceStep(
                        "PersistTool",
                        persisted ? "done" : "skip",
                        persisted ? "greeting saved, short-term memory skipped" : "knowledge base greeting not tied to a video"
                )
        );
    }

    private List<AgentTraceStep> agentIntroTrace(boolean persisted) {
        return List.of(
                new AgentTraceStep(
                        "InputGuardrail",
                        "done",
                        "question passed injection checks"
                ),
                new AgentTraceStep(
                        "AgentIntroIntent",
                        "done",
                        "agent self-introduction handled without transcript retrieval"
                ),
                new AgentTraceStep(
                        "TranscriptRetrieveTool",
                        "skip",
                        "agent intro does not require video evidence"
                ),
                new AgentTraceStep(
                        "LlmGenerateTool",
                        "skip",
                        "local agent intro response"
                ),
                new AgentTraceStep(
                        "PersistTool",
                        persisted ? "done" : "skip",
                        persisted ? "agent intro saved, short-term memory skipped" : "knowledge base intro not tied to a video"
                )
        );
    }

    private String memoryStatus(
            boolean memoryChecked,
            Optional<String> shortTermQuestion,
            Optional<String> historyQuestion
    ) {
        if (!memoryChecked) {
            return "skip";
        }
        return shortTermQuestion.isPresent() || historyQuestion.isPresent() ? "done" : "miss";
    }

    private String memoryDetail(
            boolean memoryChecked,
            Optional<String> shortTermQuestion,
            Optional<String> historyQuestion
    ) {
        if (!memoryChecked) {
            return "standalone question, cache-first path";
        }
        if (shortTermQuestion.isPresent()) {
            return shortTermMemory.source() + " short-term memory hit";
        }
        if (historyQuestion.isPresent()) {
            return "MySQL chat_message fallback hit";
        }
        return "no previous question";
    }

    private GeneratedAnswer cloudAnswer(CloudLlmResult result) {
        return GeneratedAnswer.cloud(result.content(), result);
    }

    private GeneratedAnswer prefixedCloudAnswer(CloudLlmResult result, String prefix) {
        return GeneratedAnswer.cloud(prefix + result.content(), result);
    }

    private String answerMode(List<AgentCitation> citations, GeneratedAnswer generatedAnswer, boolean knowledgeBase) {
        if (!citations.isEmpty()) {
            return knowledgeBase ? "KNOWLEDGE_BASE_CITED" : "VIDEO_CITED";
        }
        if (generatedAnswer.cloudUsed()) {
            return "GENERAL_LLM";
        }
        return "LOCAL_FALLBACK";
    }

    private String noVideoEvidencePrefix(String question, Optional<String> previousQuestion) {
        return "当前视频字幕里没有检索到和「" + compact(question, 40)
                + "」直接相关的内容。下面是基于云端 LLM 的通用回答，不代表当前视频出现过该结论：\n\n";
    }

    private String noKnowledgeBaseEvidencePrefix(String question, int videoCount) {
        return "默认知识库的 " + videoCount + " 个视频字幕里没有检索到和「" + compact(question, 40)
                + "」直接相关的内容。下面是基于云端 LLM 的通用回答，不代表这些视频出现过该结论：\n\n";
    }

    private String citationEvidenceBlock(List<AgentCitation> citations) {
        StringBuilder builder = new StringBuilder();
        for (AgentCitation citation : citations) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append("- ")
                    .append(citation.citation())
                    .append(" | videoId=")
                    .append(citation.videoId())
                    .append(" | segmentId=")
                    .append(citation.segmentId())
                    .append(" | snippet=")
                    .append(citation.snippet());
        }
        return builder.toString();
    }

    private String generationTraceDetail(GeneratedAnswer generatedAnswer) {
        if (generatedAnswer.cloudUsed()) {
            String detail = "model=" + generatedAnswer.modelName()
                    + ", durationMs=" + generatedAnswer.durationMs();
            if (generatedAnswer.totalTokens() > 0) {
                return detail + ", tokens=" + generatedAnswer.totalTokens()
                        + " (prompt=" + generatedAnswer.promptTokens()
                        + ", completion=" + generatedAnswer.completionTokens() + ")";
            }
            return detail + ", tokens=unknown";
        }
        return "local template fallback";
    }

    private String confidenceTraceStatus(String confidenceLevel) {
        if ("HIGH".equals(confidenceLevel) || "MEDIUM".equals(confidenceLevel)) {
            return "done";
        }
        if ("LOW".equals(confidenceLevel)) {
            return "warn";
        }
        return "miss";
    }

    private boolean containsAny(String text, String... candidates) {
        for (String candidate : candidates) {
            if (text.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private List<AgentCitation> buildCitations(List<Evidence> evidenceList, List<VideoAsset> videoAssets) {
        return evidenceList.stream()
                .filter(this::citableEvidence)
                .map(evidence -> {
                    TranscriptSegment hit = evidence.hit();
                    String source = videoAssets.isEmpty()
                            ? "OmniVid Demo"
                            : sourceName(videoAssets, hit.videoId());
                    return new AgentCitation(
                            source + " " + formatTime(hit.startMs()) + "-" + formatTime(hit.endMs()),
                            hit.videoId(),
                            hit.id(),
                            hit.startMs(),
                            hit.endMs(),
                            evidence.score(),
                            compact(hit.content(), 90)
                    );
                })
                .toList();
    }

    private String formatTime(long ms) {
        long totalSeconds = ms / 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return "%02d:%02d".formatted(minutes, seconds);
    }

    private String formatScore(double score) {
        return String.format(Locale.ROOT, "%.3f", score);
    }

    private String sourceName(List<VideoAsset> videoAssets, long videoId) {
        return videoAssets.stream()
                .filter(video -> video.id() == videoId)
                .map(VideoAsset::originalName)
                .findFirst()
                .orElse("video#" + videoId);
    }

    private String compact(String text, int maxLength) {
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength - 1) + "…";
    }

    private record Evidence(TranscriptSegment hit, int score, double vectorScore, int keywordScore) {
        static Evidence empty() {
            return new Evidence(null, 0, 0, 0);
        }
    }

    private record GuardrailDecision(String reason) {
    }

    private record GeneratedAnswer(
            String answer,
            boolean cloudUsed,
            String modelName,
            long durationMs,
            int promptTokens,
            int completionTokens,
            int totalTokens
    ) {
        static GeneratedAnswer local(String answer) {
            return new GeneratedAnswer(answer, false, "local-template", 0, 0, 0, 0);
        }

        static GeneratedAnswer cloud(String answer, CloudLlmResult result) {
            return new GeneratedAnswer(
                    answer,
                    true,
                    result.model(),
                    result.durationMs(),
                    result.promptTokens(),
                    result.completionTokens(),
                    result.totalTokens()
            );
        }
    }
}
