import React, { useEffect, useMemo, useRef, useState } from "react";
import { createRoot } from "react-dom/client";
import {
  ArrowUpRight,
  Bot,
  BrainCircuit,
  CheckCircle2,
  ChevronRight,
  Clock3,
  Database,
  FileText,
  Fingerprint,
  GitBranch,
  KeyRound,
  Link2,
  MessageSquareText,
  Play,
  Search,
  Send,
  ShieldCheck,
  Sparkles,
  UploadCloud,
  Video,
  Zap,
} from "lucide-react";
import "./styles.css";

const API_BASE = import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8080";

type PipelineStep = {
  label: string;
  detail: string;
  status: "done" | "running" | "waiting";
};

type VideoAsset = {
  id: number;
  md5: string;
  originalName: string;
  durationMs: number;
  status: string;
  version?: number;
};

type ProcessingJob = {
  id: number;
  videoId: number;
  currentStep: string;
  status: string;
  progress: number;
  retryCount?: number;
  errorMessage?: string | null;
  version?: number;
};

type FailedJob = {
  jobId: number;
  videoId: number;
  originalName: string;
  currentStep: string;
  progress: number;
  retryCount: number;
  errorMessage?: string | null;
  updatedAt: string;
};

type ProgressSnapshot = {
  videoId: number;
  jobId: number;
  currentStep: string;
  status: string;
  progress: number;
};

type SseInspectState = {
  status: "idle" | "opening" | "open" | "closed" | "error";
  url: string;
  disconnectCount: number;
  lastEventAt: string;
  lastSnapshot: ProgressSnapshot | null;
};

type ApiTranscriptSegment = {
  id: number;
  videoId: number;
  segmentIndex: number;
  startMs: number;
  endMs: number;
  speaker: string;
  content: string;
};

type SummaryAsset = {
  id: number;
  videoId: number;
  type: string;
  title: string;
  contentJson: string;
};

type CompleteUploadResponse = {
  deduplicated: boolean;
  video: VideoAsset;
  job: ProcessingJob;
};

type VideoDetailResponse = {
  video: VideoAsset;
  job: ProcessingJob;
  transcripts: ApiTranscriptSegment[];
  summaries: SummaryAsset[];
};

type AgentAskResponse = {
  answer: string;
  citation: string;
  citations?: AgentCitation[];
  videoId: number;
  startMs: number;
  endMs: number;
  confidenceScore: number;
  confidenceLevel: string;
  contextUsed: boolean;
  cacheHit: boolean;
  answerMode: string;
  trace?: AgentTraceStep[];
};

type AgentCitation = {
  citation: string;
  videoId: number;
  segmentId: number;
  startMs: number;
  endMs: number;
  score: number;
  snippet: string;
};

type AgentTraceStep = {
  name: string;
  status: string;
  detail: string;
};

type ApiChatMessage = {
  id: number;
  videoId: number;
  role: "user" | "assistant";
  content: string;
  citation?: string;
  createdAt: string;
};

type AgentContext = {
  videoId: number;
  windowLimit: number;
  messageCount: number;
  contextReady: boolean;
  lastUserQuestion: string;
  shortTermQuestion: string;
  memorySource: string;
};

type KnowledgeBase = {
  id: number;
  name: string;
  description: string;
  videoCount: number;
  createdAt: string;
  updatedAt: string;
};

type KnowledgeBaseDetail = {
  knowledgeBase: KnowledgeBase;
  videos: VideoAsset[];
};

type KnowledgeBaseFormState = {
  name: string;
  description: string;
};

type LlmConfig = {
  enabled: boolean;
  configured: boolean;
  baseUrl: string;
  model: string;
  timeoutSeconds: number;
  apiKeyMasked: string;
};

type LlmFormState = {
  enabled: boolean;
  providerName: string;
  apiKey: string;
  baseUrl: string;
  model: string;
  timeoutSeconds: number;
};

type LlmProvider = {
  id: number;
  providerName: string;
  baseUrl: string;
  model: string;
  apiKeyMasked: string;
  timeoutSeconds: number;
  enabled: boolean;
  active: boolean;
  lastTestStatus?: string;
  lastTestMessage?: string;
};

type LlmTestResponse = {
  success: boolean;
  message: string;
  model: string;
  durationMs: number;
  promptTokens: number;
  completionTokens: number;
  totalTokens: number;
};

type EmbeddingMode = "qwen" | "openai" | "bge";

type EmbeddingFormState = {
  providerName: string;
  mode: EmbeddingMode;
  apiKey: string;
  baseUrl: string;
  model: string;
  timeoutSeconds: number;
};

type EmbeddingProvider = {
  id: number;
  providerName: string;
  mode: EmbeddingMode;
  baseUrl: string;
  model: string;
  apiKeyMasked: string;
  timeoutSeconds: number;
  enabled: boolean;
  active: boolean;
  lastTestStatus?: string;
  lastTestMessage?: string;
};

type EmbeddingTestResponse = {
  success: boolean;
  message: string;
  provider: string;
  model: string;
  dimensions: number;
};

type RuntimeStatus = {
  profile: string;
  database: {
    connected: boolean;
    product: string;
    url: string;
    hook: string;
  };
  redis: {
    connected: boolean;
    dedupeLockMode: string;
    progressCacheMode: string;
    rateLimitMode: string;
    answerCacheMode: string;
    shortTermMemoryMode: string;
  };
  llm: {
    chatEnabled: boolean;
    chatConfigured: boolean;
    baseUrl: string;
    model: string;
    embeddingProvider: string;
    embeddingDiagnostic: string;
    embeddingIndex: string;
    embeddingDimensions: number;
    vectorStoreMode: string;
    vectorStoreConnected: boolean;
    vectorStoreEndpoint: string;
    rerankProvider: string;
    rerankDiagnostic: string;
  };
};

type MysqlExplainPlan = {
  scenario: string;
  hook: string;
  tableName: string;
  accessType: string;
  possibleKeys: string;
  keyName: string;
  rows: number;
  filtered: string;
  extra: string;
};

type MysqlExplainResponse = {
  plans: MysqlExplainPlan[];
};

type RedisInspectItem = {
  hook: string;
  pattern: string;
  sampleKey: string;
  type: string;
  ttlSeconds: number;
  exists: boolean;
  note: string;
};

type RedisInspectResponse = {
  connected: boolean;
  keys: RedisInspectItem[];
};

type ThreadPoolInspectResponse = {
  executorName: string;
  threadNamePrefix: string;
  corePoolSize: number;
  maxPoolSize: number;
  poolSize: number;
  activeCount: number;
  queueSize: number;
  queueRemainingCapacity: number;
  completedTaskCount: number;
  taskCount: number;
  rejectionPolicy: string;
  heapUsedBytes: number;
  heapMaxBytes: number;
  nonHeapUsedBytes: number;
  availableProcessors: number;
};

type VectorIndexRebuildResponse = {
  success: boolean;
  vectorStoreMode: string;
  indexName: string;
  videoCount: number;
  segmentCount: number;
  indexedCount: number;
  message: string;
};

type VectorIndexStatusResponse = {
  vectorStoreMode: string;
  connected: boolean;
  endpoint: string;
  collectionName: string;
  collectionExists: boolean;
  collectionStatus: string;
  pointsCount: number;
  indexedVectorsCount: number;
  segmentsCount: number;
  dimensions: number;
  distance: string;
  message: string;
};

type AsrDiagnostic = {
  videoId: number;
  originalName: string;
  videoStatus: string;
  asrPath: string;
  modelPath: string;
  audioFilter: string;
  language: string;
  beamSize: number;
  bestOf: number;
  maxLen: number;
  promptPreview: string;
  modelExists: boolean;
  audioExists: boolean;
  audioSizeBytes: number;
  asrJsonExists: boolean;
  asrJsonSizeBytes: number;
  asrLogExists: boolean;
  asrLogSizeBytes: number;
  transcriptCount: number;
  quality?: AsrTextQuality;
  lastJobStep: string;
  lastJobStatus: string;
  lastJobError?: string | null;
  ffmpegLogTail: string;
  asrLogTail: string;
};

type AsrTextQuality = {
  garbledRisk: boolean;
  replacementCount: number;
  controlCount: number;
  suspiciousLatinCount: number;
  traditionalCount: number;
  cjkCount: number;
  sample: string;
};

type OcrSubtitleSample = {
  segmentIndex: number;
  startMs: number;
  endMs: number;
  asrText: string;
  ocrText: string;
  fusedText: string;
  confidence: number;
  ocrAvailable: boolean;
  replacementSuggested: boolean;
  cer: number;
  similarity: number;
};

type OcrSubtitleQuality = {
  videoId: number;
  mode: string;
  ocrAvailable: boolean;
  message: string;
  sampledCount: number;
  ocrHitCount: number;
  replacementCount: number;
  appliedReplacementCount: number;
  averageCer: number;
  averageSimilarity: number;
  averageFusedCer: number;
  averageFusedSimilarity: number;
  samples: OcrSubtitleSample[];
};

type TranscriptRepairResponse = {
  videoId: number;
  scanned: number;
  repaired: number;
  vectorReindexed: boolean;
  message: string;
};

type TermGlossaryEntry = {
  id: number;
  sourcePattern: string;
  replacement: string;
  enabled: boolean;
};

type TermGlossaryFormState = {
  sourcePattern: string;
  replacement: string;
};

type UrlImportOptions = {
  cookiesFile: string;
  cookiesFromBrowser: string;
};

type ChatMessage = {
  role: "user" | "agent";
  text: string;
  citation?: string;
  citations?: AgentCitation[];
  confidenceScore?: number;
  confidenceLevel?: string;
  contextUsed?: boolean;
  cacheHit?: boolean;
  answerMode?: string;
  trace?: AgentTraceStep[];
};

type AgentMode = "video" | "knowledgeBase";
type RightWorkspaceTab = "summary" | "agent";
type DiagnosticsTab = "runtime" | "ai" | "data" | "recovery";

type WorkspaceState = {
  video: VideoAsset | null;
  job: ProcessingJob | null;
  transcripts: ApiTranscriptSegment[];
  summaries: SummaryAsset[];
  deduplicated: boolean | null;
};

const initialMessages: ChatMessage[] = [
  {
    role: "agent",
    text: "后端已接通后，我会基于真实接口返回的字幕与任务状态回答。先选择左侧本地视频文件，创建一个视频资产。",
  },
];

function answerModeLabel(mode?: string) {
  switch (mode) {
    case "VIDEO_CITED":
      return "视频证据回答";
    case "KNOWLEDGE_BASE_CITED":
      return "知识库证据回答";
    case "GENERAL_LLM":
      return "通用 LLM 回答";
    case "LOCAL_INTENT":
      return "本地意图回答";
    case "GUARDRAIL":
      return "安全拦截";
    case "LOCAL_FALLBACK":
      return "本地兜底";
    default:
      return "";
  }
}

function answerModeClass(mode?: string) {
  return (mode ?? "unknown").toLowerCase().replace(/_/g, "-");
}

function traceDetail(trace: AgentTraceStep[] | undefined, name: string) {
  return trace?.find((step) => step.name === name)?.detail ?? "";
}

function traceValue(detail: string, key: string) {
  const match = detail.match(new RegExp(`${key}=([^,]+)`));
  return match?.[1]?.trim() ?? "";
}

function traceTokenValue(detail: string) {
  const match = detail.match(/tokens=(\d+)/);
  return match?.[1] ?? traceValue(detail, "tokens");
}

function runtimeBadges(trace?: AgentTraceStep[]) {
  const vectorDetail = traceDetail(trace, "VectorRetrieveTool");
  const llmDetail = traceDetail(trace, "LlmGenerateTool");
  const vectorProvider = traceValue(vectorDetail, "provider");
  const vectorIndex = traceValue(vectorDetail, "index");
  const llmModel = traceValue(llmDetail, "model");
  const llmTokens = traceTokenValue(llmDetail);
  return [
    vectorProvider
      ? {
          label: "Retrieval",
          value: vectorIndex ? `${vectorProvider} / ${vectorIndex}` : vectorProvider,
          tone: vectorProvider.includes("fallback") ? "warn" : "done",
        }
      : null,
    llmModel
      ? {
          label: "LLM",
          value: llmTokens ? `${llmModel} / ${llmTokens} tokens` : llmModel,
          tone: "done",
        }
      : null,
  ].filter((badge): badge is { label: string; value: string; tone: string } => Boolean(badge));
}

function llmTestDetail(result: LlmTestResponse) {
  if (!result.success) {
    return result.message;
  }
  const tokenText = result.totalTokens > 0
    ? `${result.totalTokens} tokens · prompt ${result.promptTokens} / completion ${result.completionTokens}`
    : "tokens unavailable";
  return `${result.message} · ${result.durationMs}ms · ${tokenText}`;
}

const fallbackTranscript: ApiTranscriptSegment = {
  id: 0,
  videoId: 0,
  segmentIndex: 0,
  startMs: 0,
  endMs: 28_000,
  speaker: "System",
  content: "点击上传后，这里会显示后端返回的时间轴字幕。",
};

async function readApiError(response: Response) {
  const text = await response.text();
  if (!text) {
    return `Request failed: ${response.status}`;
  }
  try {
    const parsed = JSON.parse(text) as {
      message?: string;
      suggestion?: string;
      detail?: string;
    };
    const parts = [
      parsed.message,
      parsed.suggestion ? `建议：${parsed.suggestion}` : "",
      parsed.detail ? `日志：${parsed.detail}` : "",
    ].filter(Boolean);
    return parts.join("\n");
  } catch {
    return text;
  }
}

async function apiJsonRequest<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, {
    headers: {
      "Content-Type": "application/json",
      ...init?.headers,
    },
    ...init,
  });

  if (!response.ok) {
    const message = await readApiError(response);
    throw new Error(message || `Request failed: ${response.status}`);
  }

  return response.json() as Promise<T>;
}

async function apiFormRequest<T>(path: string, body: FormData): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, {
    method: "POST",
    body,
  });

  if (!response.ok) {
    const message = await readApiError(response);
    throw new Error(message || `Request failed: ${response.status}`);
  }

  return response.json() as Promise<T>;
}

async function uploadVideoFile(file: File) {
  const formData = new FormData();
  formData.append("file", file);
  return apiFormRequest<CompleteUploadResponse>("/api/videos/upload/file", formData);
}

async function importVideoUrl(url: string, options: UrlImportOptions) {
  return apiJsonRequest<CompleteUploadResponse>("/api/videos/import/url", {
    method: "POST",
    body: JSON.stringify({
      url,
      cookiesFile: options.cookiesFile.trim() || null,
      cookiesFromBrowser: options.cookiesFromBrowser,
    }),
  });
}

async function listVideos() {
  return apiJsonRequest<VideoAsset[]>("/api/videos");
}

async function getVideoDetail(videoId: number) {
  return apiJsonRequest<VideoDetailResponse>(`/api/videos/${videoId}`);
}

async function retryVideo(videoId: number) {
  return apiJsonRequest<CompleteUploadResponse>(`/api/videos/${videoId}/retry`, {
    method: "POST",
  });
}

async function listFailedJobs() {
  return apiJsonRequest<FailedJob[]>("/api/jobs/failures?limit=8");
}

async function searchTranscripts(videoId: number, keyword: string) {
  return apiJsonRequest<ApiTranscriptSegment[]>(
    `/api/videos/${videoId}/transcripts/search?q=${encodeURIComponent(keyword)}`,
  );
}

async function askAgent(videoId: number, question: string) {
  return apiJsonRequest<AgentAskResponse>(`/api/videos/${videoId}/agent/ask`, {
    method: "POST",
    body: JSON.stringify({ question }),
  });
}

async function askKnowledgeBase(question: string, knowledgeBaseId?: number | null) {
  const path = knowledgeBaseId
    ? `/api/knowledge-bases/${knowledgeBaseId}/agent/ask`
    : "/api/knowledge-bases/default/agent/ask";
  return apiJsonRequest<AgentAskResponse>(path, {
    method: "POST",
    body: JSON.stringify({ question }),
  });
}

async function listKnowledgeBases() {
  return apiJsonRequest<KnowledgeBase[]>("/api/knowledge-bases");
}

async function createKnowledgeBase(form: KnowledgeBaseFormState) {
  return apiJsonRequest<KnowledgeBase>("/api/knowledge-bases", {
    method: "POST",
    body: JSON.stringify(form),
  });
}

async function getKnowledgeBase(knowledgeBaseId: number) {
  return apiJsonRequest<KnowledgeBaseDetail>(`/api/knowledge-bases/${knowledgeBaseId}`);
}

async function addKnowledgeBaseVideo(knowledgeBaseId: number, videoId: number) {
  return apiJsonRequest<KnowledgeBaseDetail>(`/api/knowledge-bases/${knowledgeBaseId}/videos`, {
    method: "POST",
    body: JSON.stringify({ videoId }),
  });
}

async function removeKnowledgeBaseVideo(knowledgeBaseId: number, videoId: number) {
  return apiJsonRequest<KnowledgeBaseDetail>(`/api/knowledge-bases/${knowledgeBaseId}/videos/${videoId}`, {
    method: "DELETE",
  });
}

async function deleteKnowledgeBase(knowledgeBaseId: number) {
  const response = await fetch(`${API_BASE}/api/knowledge-bases/${knowledgeBaseId}`, {
    method: "DELETE",
  });
  if (!response.ok) {
    const message = await readApiError(response);
    throw new Error(message || `Request failed: ${response.status}`);
  }
}

async function getAgentMessages(videoId: number) {
  return apiJsonRequest<ApiChatMessage[]>(`/api/videos/${videoId}/agent/messages`);
}

async function getAgentContext(videoId: number) {
  return apiJsonRequest<AgentContext>(`/api/videos/${videoId}/agent/context`);
}

async function clearAgentMessages(videoId: number) {
  return apiJsonRequest<number>(`/api/videos/${videoId}/agent/messages`, {
    method: "DELETE",
  });
}

async function getLlmConfig() {
  return apiJsonRequest<LlmConfig>("/api/llm/config");
}

async function listLlmProviders() {
  return apiJsonRequest<LlmProvider[]>("/api/llm/providers");
}

async function saveLlmProvider(config: LlmFormState) {
  return apiJsonRequest<LlmProvider>("/api/llm/providers", {
    method: "POST",
    body: JSON.stringify(config),
  });
}

async function activateLlmProvider(providerId: number) {
  return apiJsonRequest<LlmProvider>(`/api/llm/providers/${providerId}/activate`, {
    method: "POST",
  });
}

async function testLlmConnection() {
  return apiJsonRequest<LlmTestResponse>("/api/llm/test", {
    method: "POST",
  });
}

async function listEmbeddingProviders() {
  return apiJsonRequest<EmbeddingProvider[]>("/api/embedding/providers");
}

async function saveEmbeddingProvider(config: EmbeddingFormState) {
  return apiJsonRequest<EmbeddingProvider>("/api/embedding/providers", {
    method: "POST",
    body: JSON.stringify(config),
  });
}

async function activateEmbeddingProvider(providerId: number) {
  return apiJsonRequest<EmbeddingProvider>(`/api/embedding/providers/${providerId}/activate`, {
    method: "POST",
  });
}

async function testEmbeddingConnection() {
  return apiJsonRequest<EmbeddingTestResponse>("/api/embedding/test", {
    method: "POST",
  });
}

async function getRuntimeStatus() {
  return apiJsonRequest<RuntimeStatus>("/api/runtime/status");
}

async function getMysqlExplain() {
  return apiJsonRequest<MysqlExplainResponse>("/api/mysql/explain");
}

async function getRedisInspect() {
  return apiJsonRequest<RedisInspectResponse>("/api/redis/inspect");
}

async function getThreadPoolInspect() {
  return apiJsonRequest<ThreadPoolInspectResponse>("/api/jvm/thread-pool");
}

async function getVectorIndexStatus() {
  return apiJsonRequest<VectorIndexStatusResponse>("/api/vector-index/status");
}

async function getAsrDiagnostic(videoId: number) {
  return apiJsonRequest<AsrDiagnostic>(`/api/videos/${videoId}/asr/diagnostics`);
}

async function evaluateOcrQuality(videoId: number) {
  return apiJsonRequest<OcrSubtitleQuality>(`/api/videos/${videoId}/asr/evaluate-ocr`);
}

async function fuseOcrQuality(videoId: number) {
  return apiJsonRequest<OcrSubtitleQuality>(`/api/videos/${videoId}/asr/fuse-ocr`, {
    method: "POST",
  });
}

async function alignOcrQuality(videoId: number) {
  return apiJsonRequest<OcrSubtitleQuality>(`/api/videos/${videoId}/asr/align-ocr`, {
    method: "POST",
  });
}

async function refineLowConfidenceSubtitles(videoId: number) {
  return apiJsonRequest<OcrSubtitleQuality>(`/api/videos/${videoId}/asr/refine-low-confidence`, {
    method: "POST",
  });
}

async function repairTranscriptText(videoId: number) {
  return apiJsonRequest<TranscriptRepairResponse>(`/api/videos/${videoId}/asr/repair-encoding`, {
    method: "POST",
  });
}

async function reprocessAsr(videoId: number) {
  return apiJsonRequest<CompleteUploadResponse>(`/api/videos/${videoId}/asr/reprocess`, {
    method: "POST",
  });
}

async function listTermGlossary() {
  return apiJsonRequest<TermGlossaryEntry[]>("/api/asr/glossary");
}

async function createTermGlossary(form: TermGlossaryFormState) {
  return apiJsonRequest<TermGlossaryEntry>("/api/asr/glossary", {
    method: "POST",
    body: JSON.stringify(form),
  });
}

async function setTermGlossaryEnabled(entryId: number, enabled: boolean) {
  return apiJsonRequest<TermGlossaryEntry>(`/api/asr/glossary/${entryId}/enabled?enabled=${enabled}`, {
    method: "PUT",
  });
}

async function deleteTermGlossary(entryId: number) {
  const response = await fetch(`${API_BASE}/api/asr/glossary/${entryId}`, {
    method: "DELETE",
  });
  if (!response.ok) {
    const message = await readApiError(response);
    throw new Error(message || `Request failed: ${response.status}`);
  }
}

async function rebuildVectorIndex() {
  return apiJsonRequest<VectorIndexRebuildResponse>("/api/vector-index/rebuild", {
    method: "POST",
  });
}

function App() {
  const [activeSegment, setActiveSegment] = useState(0);
  const [agentMode, setAgentMode] = useState<AgentMode>("video");
  const [rightWorkspaceTab, setRightWorkspaceTab] = useState<RightWorkspaceTab>("agent");
  const [query, setQuery] = useState("");
  const [messages, setMessages] = useState<ChatMessage[]>(initialMessages);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState("");
  const [videoUrl, setVideoUrl] = useState("");
  const [urlImportOptions, setUrlImportOptions] = useState<UrlImportOptions>({
    cookiesFile: "",
    cookiesFromBrowser: "none",
  });
  const [playbackMs, setPlaybackMs] = useState(0);
  const [videos, setVideos] = useState<VideoAsset[]>([]);
  const [failedJobs, setFailedJobs] = useState<FailedJob[]>([]);
  const [runtimeStatus, setRuntimeStatus] = useState<RuntimeStatus | null>(null);
  const [mysqlExplain, setMysqlExplain] = useState<MysqlExplainPlan[]>([]);
  const [redisInspect, setRedisInspect] = useState<RedisInspectResponse | null>(null);
  const [threadPoolInspect, setThreadPoolInspect] = useState<ThreadPoolInspectResponse | null>(null);
  const [vectorIndexInspect, setVectorIndexInspect] = useState<VectorIndexStatusResponse | null>(null);
  const [asrDiagnostic, setAsrDiagnostic] = useState<AsrDiagnostic | null>(null);
  const [ocrQuality, setOcrQuality] = useState<OcrSubtitleQuality | null>(null);
  const [ocrStatus, setOcrStatus] = useState("");
  const [isEvaluatingOcr, setIsEvaluatingOcr] = useState(false);
  const [isFusingOcr, setIsFusingOcr] = useState(false);
  const [isAligningOcr, setIsAligningOcr] = useState(false);
  const [isRefiningLowConfidence, setIsRefiningLowConfidence] = useState(false);
  const [textRepairStatus, setTextRepairStatus] = useState("");
  const [isRepairingText, setIsRepairingText] = useState(false);
  const [termGlossary, setTermGlossary] = useState<TermGlossaryEntry[]>([]);
  const [termGlossaryForm, setTermGlossaryForm] = useState<TermGlossaryFormState>({
    sourcePattern: "",
    replacement: "",
  });
  const [termGlossaryStatus, setTermGlossaryStatus] = useState("");
  const [isSavingTermGlossary, setIsSavingTermGlossary] = useState(false);
  const [pendingTermGlossaryId, setPendingTermGlossaryId] = useState<number | null>(null);
  const [sseInspect, setSseInspect] = useState<SseInspectState>({
    status: "idle",
    url: "",
    disconnectCount: 0,
    lastEventAt: "",
    lastSnapshot: null,
  });
  const [vectorIndexStatus, setVectorIndexStatus] = useState("");
  const [isRebuildingVectorIndex, setIsRebuildingVectorIndex] = useState(false);
  const [agentContext, setAgentContext] = useState<AgentContext | null>(null);
  const [knowledgeBases, setKnowledgeBases] = useState<KnowledgeBase[]>([]);
  const [activeKnowledgeBaseId, setActiveKnowledgeBaseId] = useState<number | null>(null);
  const [activeKnowledgeBaseDetail, setActiveKnowledgeBaseDetail] = useState<KnowledgeBaseDetail | null>(null);
  const [knowledgeBaseForm, setKnowledgeBaseForm] = useState<KnowledgeBaseFormState>({
    name: "",
    description: "",
  });
  const [knowledgeBaseStatus, setKnowledgeBaseStatus] = useState("");
  const [isSavingKnowledgeBase, setIsSavingKnowledgeBase] = useState(false);
  const [pendingKnowledgeBaseVideoId, setPendingKnowledgeBaseVideoId] = useState<number | null>(null);
  const [transcriptSearchQuery, setTranscriptSearchQuery] = useState("");
  const [transcriptSearchResults, setTranscriptSearchResults] = useState<ApiTranscriptSegment[]>([]);
  const [isSearchingTranscripts, setIsSearchingTranscripts] = useState(false);
  const [llmConfig, setLlmConfig] = useState<LlmConfig | null>(null);
  const [llmProviders, setLlmProviders] = useState<LlmProvider[]>([]);
  const [llmForm, setLlmForm] = useState<LlmFormState>({
    enabled: false,
    providerName: "DeepSeek",
    apiKey: "",
    baseUrl: "https://api.deepseek.com/v1",
    model: "deepseek-chat",
    timeoutSeconds: 60,
  });
  const [llmStatus, setLlmStatus] = useState("");
  const [isSavingLlm, setIsSavingLlm] = useState(false);
  const [isTestingLlm, setIsTestingLlm] = useState(false);
  const [activatingLlmId, setActivatingLlmId] = useState<number | null>(null);
  const [embeddingProviders, setEmbeddingProviders] = useState<EmbeddingProvider[]>([]);
  const [embeddingForm, setEmbeddingForm] = useState<EmbeddingFormState>({
    providerName: embeddingDefaults.qwen.providerName,
    mode: "qwen",
    apiKey: "",
    baseUrl: embeddingDefaults.qwen.baseUrl,
    model: embeddingDefaults.qwen.model,
    timeoutSeconds: 30,
  });
  const [embeddingStatus, setEmbeddingStatus] = useState("");
  const [isSavingEmbedding, setIsSavingEmbedding] = useState(false);
  const [isTestingEmbedding, setIsTestingEmbedding] = useState(false);
  const [activatingEmbeddingId, setActivatingEmbeddingId] = useState<number | null>(null);
  const [diagnosticsTab, setDiagnosticsTab] = useState<DiagnosticsTab>("runtime");
  const [diagnosticsOpen, setDiagnosticsOpen] = useState(false);
  const [llmOpen, setLlmOpen] = useState(false);
  const [embeddingOpen, setEmbeddingOpen] = useState(false);
  const [libraryOpen, setLibraryOpen] = useState(false);
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const [workspace, setWorkspace] = useState<WorkspaceState>({
    video: null,
    job: null,
    transcripts: [],
    summaries: [],
    deduplicated: null,
  });

  const activeTranscript = workspace.transcripts[activeSegment] ?? fallbackTranscript;
  const steps = useMemo(() => buildPipelineSteps(workspace.job), [workspace.job]);
  const latestAgentMessage = useMemo(
    () => [...messages].reverse().find((message) => message.role === "agent" && message.trace?.length),
    [messages],
  );

  function resetTranscriptSearch() {
    setTranscriptSearchQuery("");
    setTranscriptSearchResults([]);
    setIsSearchingTranscripts(false);
  }

  function resetOcrQuality() {
    setOcrQuality(null);
    setOcrStatus("");
    setTextRepairStatus("");
  }

  useEffect(() => {
    refreshVideos();
    refreshFailedJobs();
    refreshMysqlExplain();
    refreshRedisInspect();
    refreshThreadPoolInspect();
    refreshVectorIndexInspect();
    refreshLlmConfig();
    refreshEmbeddingProviders();
    refreshKnowledgeBases();
    refreshRuntimeStatus();
    refreshTermGlossary();
  }, []);

  useEffect(() => {
    if (workspace.video) {
      refreshAsrDiagnostic(workspace.video.id);
    } else {
      setAsrDiagnostic(null);
      setOcrQuality(null);
      setOcrStatus("");
    }
  }, [workspace.video?.id, workspace.job?.status, workspace.transcripts.length]);

  useEffect(() => {
    if (!workspace.video || !workspace.job || workspace.job.status !== "RUNNING") {
      setSseInspect((current) =>
        current.status === "open" || current.status === "opening" ? { ...current, status: "idle", url: "" } : current,
      );
      return;
    }

    const streamUrl = `${API_BASE}/api/videos/${workspace.video.id}/progress/stream`;
    setSseInspect((current) => ({
      ...current,
      status: "opening",
      url: streamUrl,
    }));
    const source = new EventSource(streamUrl);
    let closed = false;

    source.onopen = () => {
      setSseInspect((current) => ({
        ...current,
        status: "open",
      }));
    };

    source.addEventListener("progress", async (event) => {
      try {
        const snapshot = JSON.parse((event as MessageEvent).data) as ProgressSnapshot;
        setSseInspect((current) => ({
          ...current,
          status: "open",
          lastEventAt: new Date().toLocaleTimeString(),
          lastSnapshot: snapshot,
        }));
        setWorkspace((current) => ({
          ...current,
          job: current.job
            ? {
                ...current.job,
                id: snapshot.jobId,
                videoId: snapshot.videoId,
                currentStep: snapshot.currentStep,
                status: snapshot.status,
                progress: snapshot.progress,
                version: current.job.version,
              }
            : current.job,
        }));

        if (snapshot.status !== "RUNNING" && !closed) {
          closed = true;
          setSseInspect((current) => ({
            ...current,
            status: "closed",
          }));
          source.close();
          const detail = await getVideoDetail(snapshot.videoId);
          setWorkspace((current) => ({
            ...current,
            video: detail.video,
            job: detail.job,
            transcripts: detail.transcripts,
            summaries: detail.summaries,
          }));
          resetTranscriptSearch();
          await refreshVideos();
          await refreshFailedJobs();
          await refreshThreadPoolInspect();
          if (detail.job.status === "DONE") {
            await loadVideoMessages(
              detail.video.id,
              buildVideoIntro(detail, `处理完成：已生成 ${detail.transcripts.length} 条 ASR 字幕和 ${detail.summaries.length} 份结构化总结。`),
              detail.transcripts,
            );
          }
        }
      } catch (exception) {
        setError(exception instanceof Error ? exception.message : "任务进度推送处理失败");
      }
    });

    source.onerror = () => {
      if (!closed) {
        closed = true;
        setSseInspect((current) => ({
          ...current,
          status: "error",
          disconnectCount: current.disconnectCount + 1,
        }));
        setError("任务进度连接断开");
        source.close();
      }
    };

    return () => {
      closed = true;
      setSseInspect((current) =>
        current.status === "open" || current.status === "opening" ? { ...current, status: "closed" } : current,
      );
      source.close();
    };
  }, [workspace.video?.id, workspace.job?.status]);

  async function refreshVideos() {
    try {
      setVideos(await listVideos());
    } catch (exception) {
      setError(exception instanceof Error ? exception.message : "视频列表加载失败");
    }
  }

  async function refreshFailedJobs() {
    try {
      setFailedJobs(await listFailedJobs());
    } catch {
      setFailedJobs([]);
    }
  }

  async function refreshLlmConfig() {
    try {
      const [config, providers] = await Promise.all([getLlmConfig(), listLlmProviders()]);
      applyLlmConfig(config);
      setLlmProviders(providers);
      const activeProvider = providers.find((provider) => provider.active);
      if (activeProvider) {
        setLlmForm((current) => ({
          ...current,
          providerName: activeProvider.providerName,
          apiKey: "",
          baseUrl: activeProvider.baseUrl,
          model: activeProvider.model,
          timeoutSeconds: activeProvider.timeoutSeconds,
        }));
      }
    } catch (exception) {
      setLlmStatus(exception instanceof Error ? exception.message : "LLM 配置加载失败");
    }
  }

  async function refreshEmbeddingProviders() {
    try {
      const providers = await listEmbeddingProviders();
      setEmbeddingProviders(providers);
      const activeProvider = providers.find((provider) => provider.active);
      if (activeProvider) {
        setEmbeddingForm((current) => ({
          ...current,
          providerName: activeProvider.providerName,
          mode: activeProvider.mode,
          apiKey: "",
          baseUrl: activeProvider.baseUrl,
          model: activeProvider.model,
          timeoutSeconds: activeProvider.timeoutSeconds,
        }));
      }
    } catch (exception) {
      setEmbeddingStatus(exception instanceof Error ? exception.message : "Embedding 配置加载失败");
    }
  }

  async function refreshRuntimeStatus() {
    try {
      setRuntimeStatus(await getRuntimeStatus());
    } catch {
      setRuntimeStatus(null);
    }
  }

  async function refreshMysqlExplain() {
    try {
      const result = await getMysqlExplain();
      setMysqlExplain(result.plans);
    } catch {
      setMysqlExplain([]);
    }
  }

  async function refreshRedisInspect() {
    try {
      setRedisInspect(await getRedisInspect());
    } catch {
      setRedisInspect(null);
    }
  }

  async function refreshThreadPoolInspect() {
    try {
      setThreadPoolInspect(await getThreadPoolInspect());
    } catch {
      setThreadPoolInspect(null);
    }
  }

  async function refreshVectorIndexInspect() {
    try {
      setVectorIndexInspect(await getVectorIndexStatus());
    } catch {
      setVectorIndexInspect(null);
    }
  }

  async function refreshAsrDiagnostic(videoId = workspace.video?.id) {
    if (!videoId) {
      setAsrDiagnostic(null);
      return;
    }
    try {
      setAsrDiagnostic(await getAsrDiagnostic(videoId));
    } catch (exception) {
      setError(exception instanceof Error ? exception.message : "ASR diagnostics load failed");
    }
  }

  async function refreshTermGlossary() {
    try {
      setTermGlossary(await listTermGlossary());
    } catch (exception) {
      setTermGlossaryStatus(exception instanceof Error ? exception.message : "Term glossary load failed");
    }
  }

  async function refreshKnowledgeBases(nextActiveId = activeKnowledgeBaseId) {
    try {
      const bases = await listKnowledgeBases();
      setKnowledgeBases(bases);
      const resolvedId = nextActiveId ?? bases[0]?.id ?? null;
      setActiveKnowledgeBaseId(resolvedId);
      if (resolvedId) {
        setActiveKnowledgeBaseDetail(await getKnowledgeBase(resolvedId));
      } else {
        setActiveKnowledgeBaseDetail(null);
      }
    } catch (exception) {
      setKnowledgeBaseStatus(exception instanceof Error ? exception.message : "知识库加载失败");
    }
  }

  async function handleEvaluateOcr() {
    if (!workspace.video) return;
    setIsEvaluatingOcr(true);
    setOcrStatus("");
    try {
      const result = await evaluateOcrQuality(workspace.video.id);
      setOcrQuality(result);
      setOcrStatus(
        `OCR 命中 ${result.ocrHitCount}/${result.sampledCount}，当前相似度 ${formatPercent(result.averageSimilarity)}，融合后 ${formatPercent(result.averageFusedSimilarity)}`,
      );
    } catch (exception) {
      setOcrStatus(exception instanceof Error ? exception.message : "OCR quality evaluation failed");
    } finally {
      setIsEvaluatingOcr(false);
    }
  }

  async function handleFuseOcr() {
    if (!workspace.video) return;
    setIsFusingOcr(true);
    setOcrStatus("");
    try {
      const result = await fuseOcrQuality(workspace.video.id);
      const detail = await getVideoDetail(workspace.video.id);
      setWorkspace((current) => ({
        ...current,
        video: detail.video,
        job: detail.job,
        transcripts: detail.transcripts,
        summaries: detail.summaries,
      }));
      setOcrQuality(result);
      setOcrStatus(
        `已写回 ${result.appliedReplacementCount} 处修复，当前相似度 ${formatPercent(result.averageSimilarity)}，融合后 ${formatPercent(result.averageFusedSimilarity)}`,
      );
      resetTranscriptSearch();
      await refreshAsrDiagnostic(detail.video.id);
      await refreshVectorIndexInspect();
    } catch (exception) {
      setOcrStatus(exception instanceof Error ? exception.message : "OCR fusion failed");
    } finally {
      setIsFusingOcr(false);
    }
  }

  async function handleAlignOcr() {
    if (!workspace.video) return;
    setIsAligningOcr(true);
    setOcrStatus("");
    try {
      const result = await alignOcrQuality(workspace.video.id);
      const detail = await getVideoDetail(workspace.video.id);
      setWorkspace((current) => ({
        ...current,
        video: detail.video,
        job: detail.job,
        transcripts: detail.transcripts,
        summaries: detail.summaries,
      }));
      setOcrQuality(result);
      setOcrStatus(
        `Strong OCR align applied=${result.appliedReplacementCount}, sampled=${result.sampledCount}, fused=${formatPercent(result.averageFusedSimilarity)}`,
      );
      resetTranscriptSearch();
      await refreshAsrDiagnostic(detail.video.id);
      await refreshVectorIndexInspect();
    } catch (exception) {
      setOcrStatus(exception instanceof Error ? exception.message : "Strong OCR align failed");
    } finally {
      setIsAligningOcr(false);
    }
  }

  async function handleRefineLowConfidence() {
    if (!workspace.video) return;
    setIsRefiningLowConfidence(true);
    setOcrStatus("");
    try {
      const result = await refineLowConfidenceSubtitles(workspace.video.id);
      const detail = await getVideoDetail(workspace.video.id);
      setWorkspace((current) => ({
        ...current,
        video: detail.video,
        job: detail.job,
        transcripts: detail.transcripts,
        summaries: detail.summaries,
      }));
      setOcrQuality(result);
      setOcrStatus(
        `Low-confidence refine applied=${result.appliedReplacementCount}, sampled=${result.sampledCount}, fused=${formatPercent(result.averageFusedSimilarity)}`,
      );
      resetTranscriptSearch();
      await refreshAsrDiagnostic(detail.video.id);
      await refreshVectorIndexInspect();
    } catch (exception) {
      setOcrStatus(exception instanceof Error ? exception.message : "Low-confidence refine failed");
    } finally {
      setIsRefiningLowConfidence(false);
    }
  }

  async function handleRepairTranscriptText() {
    if (!workspace.video) return;
    setIsRepairingText(true);
    setTextRepairStatus("");
    try {
      const result = await repairTranscriptText(workspace.video.id);
      const detail = await getVideoDetail(workspace.video.id);
      setWorkspace((current) => ({
        ...current,
        video: detail.video,
        job: detail.job,
        transcripts: detail.transcripts,
        summaries: detail.summaries,
      }));
      resetTranscriptSearch();
      await refreshAsrDiagnostic(detail.video.id);
      await refreshVectorIndexInspect();
      setTextRepairStatus(
        `${result.message} · scanned=${result.scanned}, repaired=${result.repaired}, vector=${result.vectorReindexed ? "reindexed" : "unchanged"}`,
      );
    } catch (exception) {
      setTextRepairStatus(exception instanceof Error ? exception.message : "Transcript text repair failed");
    } finally {
      setIsRepairingText(false);
    }
  }

  async function handleAddTermGlossary() {
    setIsSavingTermGlossary(true);
    setTermGlossaryStatus("");
    try {
      await createTermGlossary(termGlossaryForm);
      setTermGlossaryForm({ sourcePattern: "", replacement: "" });
      await refreshTermGlossary();
      setTermGlossaryStatus("Term glossary saved");
    } catch (exception) {
      setTermGlossaryStatus(exception instanceof Error ? exception.message : "Term glossary save failed");
    } finally {
      setIsSavingTermGlossary(false);
    }
  }

  async function handleToggleTermGlossary(entry: TermGlossaryEntry) {
    setPendingTermGlossaryId(entry.id);
    setTermGlossaryStatus("");
    try {
      await setTermGlossaryEnabled(entry.id, !entry.enabled);
      await refreshTermGlossary();
      setTermGlossaryStatus(entry.enabled ? "Term glossary disabled" : "Term glossary enabled");
    } catch (exception) {
      setTermGlossaryStatus(exception instanceof Error ? exception.message : "Term glossary update failed");
    } finally {
      setPendingTermGlossaryId(null);
    }
  }

  async function handleDeleteTermGlossary(entryId: number) {
    setPendingTermGlossaryId(entryId);
    setTermGlossaryStatus("");
    try {
      await deleteTermGlossary(entryId);
      await refreshTermGlossary();
      setTermGlossaryStatus("Term glossary deleted");
    } catch (exception) {
      setTermGlossaryStatus(exception instanceof Error ? exception.message : "Term glossary delete failed");
    } finally {
      setPendingTermGlossaryId(null);
    }
  }

  async function handleRebuildVectorIndex() {
    setIsRebuildingVectorIndex(true);
    setVectorIndexStatus("");
    try {
      const result = await rebuildVectorIndex();
      await refreshRuntimeStatus();
      await refreshVectorIndexInspect();
      const prefix = result.success ? "重建完成" : "重建未完成";
      setVectorIndexStatus(
        `${prefix}: ${result.message} · ${result.indexedCount}/${result.segmentCount} segments · ${result.indexName}`,
      );
    } catch (exception) {
      setVectorIndexStatus(exception instanceof Error ? exception.message : "向量索引重建失败");
    } finally {
      setIsRebuildingVectorIndex(false);
    }
  }

  function applyLlmConfig(config: LlmConfig) {
    setLlmConfig(config);
    setLlmForm((current) => ({
      ...current,
      enabled: config.enabled,
      apiKey: "",
      baseUrl: config.baseUrl,
      model: config.model,
      timeoutSeconds: config.timeoutSeconds,
    }));
  }

  async function handleSaveLlmConfig() {
    setIsSavingLlm(true);
    setLlmStatus("");
    try {
      await saveLlmProvider({
        ...llmForm,
        enabled: true,
        timeoutSeconds: Number(llmForm.timeoutSeconds) || 60,
      });
      await refreshLlmConfig();
      await refreshRuntimeStatus();
      setLlmStatus("LLM Provider 已保存并启用");
    } catch (exception) {
      setLlmStatus(exception instanceof Error ? exception.message : "LLM 配置保存失败");
    } finally {
      setIsSavingLlm(false);
    }
  }

  async function handleTestLlmConnection() {
    setIsTestingLlm(true);
    setLlmStatus("");
    try {
      const result = await testLlmConnection();
      setLlmStatus(`${result.success ? "连接成功" : "连接失败"}：${llmTestDetail(result)}`);
      await refreshLlmConfig();
      await refreshRuntimeStatus();
    } catch (exception) {
      setLlmStatus(exception instanceof Error ? exception.message : "LLM 连接测试失败");
    } finally {
      setIsTestingLlm(false);
    }
  }

  async function handleActivateLlmProvider(providerId: number) {
    setActivatingLlmId(providerId);
    setLlmStatus("");
    try {
      await activateLlmProvider(providerId);
      await refreshLlmConfig();
      await refreshRuntimeStatus();
      setLlmStatus("LLM Provider 已启用");
    } catch (exception) {
      setLlmStatus(exception instanceof Error ? exception.message : "LLM Provider 启用失败");
    } finally {
      setActivatingLlmId(null);
    }
  }

  function handleEmbeddingModeChange(mode: EmbeddingMode) {
    const defaults = embeddingDefaults[mode];
    setEmbeddingForm((current) => ({
      ...current,
      mode,
      providerName: defaults.providerName,
      baseUrl: defaults.baseUrl,
      model: defaults.model,
    }));
  }

  async function handleSaveEmbeddingProvider() {
    setIsSavingEmbedding(true);
    setEmbeddingStatus("");
    try {
      await saveEmbeddingProvider({
        ...embeddingForm,
        timeoutSeconds: Number(embeddingForm.timeoutSeconds) || 30,
      });
      await refreshEmbeddingProviders();
      await refreshRuntimeStatus();
      await refreshVectorIndexInspect();
      setEmbeddingStatus("Embedding Provider 已保存并启用，建议重建向量索引");
    } catch (exception) {
      setEmbeddingStatus(exception instanceof Error ? exception.message : "Embedding 配置保存失败");
    } finally {
      setIsSavingEmbedding(false);
    }
  }

  async function handleTestEmbeddingConnection() {
    setIsTestingEmbedding(true);
    setEmbeddingStatus("");
    try {
      const result = await testEmbeddingConnection();
      setEmbeddingStatus(
        `${result.success ? "连接成功" : "连接失败"}：${result.message} · ${result.dimensions} dims`,
      );
      await refreshEmbeddingProviders();
      await refreshRuntimeStatus();
    } catch (exception) {
      setEmbeddingStatus(exception instanceof Error ? exception.message : "Embedding 连接测试失败");
    } finally {
      setIsTestingEmbedding(false);
    }
  }

  async function handleActivateEmbeddingProvider(providerId: number) {
    setActivatingEmbeddingId(providerId);
    setEmbeddingStatus("");
    try {
      await activateEmbeddingProvider(providerId);
      await refreshEmbeddingProviders();
      await refreshRuntimeStatus();
      await refreshVectorIndexInspect();
      setEmbeddingStatus("Embedding Provider 已启用，建议重建向量索引");
    } catch (exception) {
      setEmbeddingStatus(exception instanceof Error ? exception.message : "Embedding Provider 启用失败");
    } finally {
      setActivatingEmbeddingId(null);
    }
  }

  async function handleCreateKnowledgeBase() {
    setIsSavingKnowledgeBase(true);
    setKnowledgeBaseStatus("");
    try {
      const created = await createKnowledgeBase(knowledgeBaseForm);
      setKnowledgeBaseForm({ name: "", description: "" });
      setAgentMode("knowledgeBase");
      await refreshKnowledgeBases(created.id);
      setKnowledgeBaseStatus(`已创建知识库：${created.name}`);
    } catch (exception) {
      setKnowledgeBaseStatus(exception instanceof Error ? exception.message : "知识库创建失败");
    } finally {
      setIsSavingKnowledgeBase(false);
    }
  }

  async function handleSelectKnowledgeBase(knowledgeBaseId: number) {
    setActiveKnowledgeBaseId(knowledgeBaseId);
    setKnowledgeBaseStatus("");
    try {
      setActiveKnowledgeBaseDetail(await getKnowledgeBase(knowledgeBaseId));
    } catch (exception) {
      setKnowledgeBaseStatus(exception instanceof Error ? exception.message : "知识库详情加载失败");
    }
  }

  async function handleAddVideoToKnowledgeBase(videoId: number) {
    if (!activeKnowledgeBaseId) return;
    setPendingKnowledgeBaseVideoId(videoId);
    setKnowledgeBaseStatus("");
    try {
      setActiveKnowledgeBaseDetail(await addKnowledgeBaseVideo(activeKnowledgeBaseId, videoId));
      await refreshKnowledgeBases(activeKnowledgeBaseId);
      setKnowledgeBaseStatus("视频已加入知识库");
    } catch (exception) {
      setKnowledgeBaseStatus(exception instanceof Error ? exception.message : "加入知识库失败");
    } finally {
      setPendingKnowledgeBaseVideoId(null);
    }
  }

  async function handleRemoveVideoFromKnowledgeBase(videoId: number) {
    if (!activeKnowledgeBaseId) return;
    setPendingKnowledgeBaseVideoId(videoId);
    setKnowledgeBaseStatus("");
    try {
      setActiveKnowledgeBaseDetail(await removeKnowledgeBaseVideo(activeKnowledgeBaseId, videoId));
      await refreshKnowledgeBases(activeKnowledgeBaseId);
      setKnowledgeBaseStatus("视频已移出知识库");
    } catch (exception) {
      setKnowledgeBaseStatus(exception instanceof Error ? exception.message : "移出知识库失败");
    } finally {
      setPendingKnowledgeBaseVideoId(null);
    }
  }

  async function handleDeleteKnowledgeBase() {
    if (!activeKnowledgeBaseId) return;
    setKnowledgeBaseStatus("");
    try {
      await deleteKnowledgeBase(activeKnowledgeBaseId);
      setActiveKnowledgeBaseId(null);
      setActiveKnowledgeBaseDetail(null);
      await refreshKnowledgeBases(null);
      setKnowledgeBaseStatus("知识库已删除");
    } catch (exception) {
      setKnowledgeBaseStatus(exception instanceof Error ? exception.message : "知识库删除失败");
    }
  }

  async function loadVideoMessages(videoId: number, intro: ChatMessage, transcriptRows: ApiTranscriptSegment[]) {
    const history = await getAgentMessages(videoId);
    setAgentContext(await getAgentContext(videoId));
    const restored = history.map((message): ChatMessage => ({
      role: message.role === "assistant" ? "agent" : "user",
      text: message.content,
      citation: message.citation || undefined,
      citations: buildHistoryCitation(videoId, message.citation, transcriptRows),
    }));
    setMessages([intro, ...restored]);
  }

  function buildVideoIntro(detail: VideoDetailResponse, text: string): ChatMessage {
    return {
      role: "agent",
      text,
      citation: detail.transcripts[0]
        ? `OmniVid Demo ${formatTime(detail.transcripts[0].startMs)}-${formatTime(detail.transcripts[0].endMs)}`
        : undefined,
    };
  }

  function buildWorkspaceIntro(text: string): ChatMessage {
    return {
      role: "agent",
      text,
      citation: workspace.transcripts[0]
        ? `OmniVid Demo ${formatTime(workspace.transcripts[0].startMs)}-${formatTime(workspace.transcripts[0].endMs)}`
        : undefined,
    };
  }

  async function loadVideo(videoId: number) {
    setError("");
    try {
      const detail = await getVideoDetail(videoId);
      setWorkspace({
        video: detail.video,
        job: detail.job,
        transcripts: detail.transcripts,
        summaries: detail.summaries,
        deduplicated: true,
      });
      setActiveSegment(0);
      setPlaybackMs(0);
      resetTranscriptSearch();
      await loadVideoMessages(
        videoId,
        buildVideoIntro(detail, `已切换到 ${detail.video.originalName}，当前可查看 ${detail.transcripts.length} 条 ASR 字幕和 ${detail.summaries.length} 份结构化总结。`),
        detail.transcripts,
      );
    } catch (exception) {
      setError(exception instanceof Error ? exception.message : "视频详情加载失败");
    }
  }

  async function applyUploadResult(upload: CompleteUploadResponse, introText: string) {
    const detail = await getVideoDetail(upload.video.id);
    await refreshVideos();
    setWorkspace({
      video: detail.video,
      job: detail.job,
      transcripts: detail.transcripts,
      summaries: detail.summaries,
      deduplicated: upload.deduplicated,
    });
    setActiveSegment(0);
    setPlaybackMs(0);
    resetTranscriptSearch();
    await loadVideoMessages(
      detail.video.id,
      buildVideoIntro(detail, introText),
      detail.transcripts,
    );
  }

  async function handleUpload(file: File) {
    setIsLoading(true);
    setError("");

    try {
      const upload = await uploadVideoFile(file);
      await applyUploadResult(
        upload,
        upload.deduplicated
          ? "命中去重：已复用这个视频已有的 ASR 字幕和结构化总结。当前 Agent 仍是轻量检索回答，下一步接 LLM/RAG。"
          : "上传完成：后端已保存文件、计算 MD5，并创建异步解析任务。页面会自动刷新任务进度、字幕和总结。",
      );
    } catch (exception) {
      setError(exception instanceof Error ? exception.message : "后端请求失败");
    } finally {
      setIsLoading(false);
    }
  }

  async function handleImportUrl(url: string) {
    const trimmedUrl = url.trim();
    if (!trimmedUrl) return;

    setIsLoading(true);
    setError("");

    try {
      const upload = await importVideoUrl(trimmedUrl, urlImportOptions);
      setVideoUrl("");
      await applyUploadResult(
        upload,
        upload.deduplicated
          ? "URL 命中去重：已复用这个视频已有的 ASR 字幕和结构化总结。"
          : "URL 解析完成：后端已下载平台视频、计算 MD5，并创建异步解析任务。",
      );
    } catch (exception) {
      setError(exception instanceof Error ? exception.message : "URL 解析失败");
    } finally {
      setIsLoading(false);
    }
  }

  async function handleRetryVideo() {
    if (!workspace.video) return;
    setIsLoading(true);
    setError("");

    try {
      const upload = await retryVideo(workspace.video.id);
      await applyUploadResult(
        upload,
        `已重新投递 ${upload.video.originalName} 的失败解析任务，后台会继续走 ffmpeg -> ASR -> 总结 DAG。`,
      );
    } catch (exception) {
      setError(exception instanceof Error ? exception.message : "重试解析失败");
    } finally {
      setIsLoading(false);
    }
  }

  async function handleReprocessAsr() {
    if (!workspace.video) return;
    setIsLoading(true);
    setError("");
    resetOcrQuality();

    try {
      const upload = await reprocessAsr(workspace.video.id);
      await applyUploadResult(
        upload,
        `已重新识别 ${upload.video.originalName} 的字幕，新的 ASR 热词提示会用于本次转写。`,
      );
      setDiagnosticsTab("recovery");
      await refreshThreadPoolInspect();
    } catch (exception) {
      setError(exception instanceof Error ? exception.message : "重新识别字幕失败");
    } finally {
      setIsLoading(false);
    }
  }

  async function handleRetryFailedJob(videoId: number) {
    setIsLoading(true);
    setError("");

    try {
      const upload = await retryVideo(videoId);
      await applyUploadResult(
        upload,
        `Retry queued: ${upload.video.originalName} will re-run ffmpeg -> ASR -> summary DAG.`,
      );
      await refreshFailedJobs();
      await refreshThreadPoolInspect();
    } catch (exception) {
      setError(exception instanceof Error ? exception.message : "Retry failed");
    } finally {
      setIsLoading(false);
    }
  }

  async function handleAskAgent() {
    const trimmedQuery = query.trim();
    if (!trimmedQuery || (agentMode === "video" && !workspace.video)) return;

    setQuery("");
    setMessages((current) => [...current, { role: "user", text: trimmedQuery }]);

    try {
      const answer = agentMode === "knowledgeBase"
        ? await askKnowledgeBase(trimmedQuery, activeKnowledgeBaseId)
        : await askAgent(workspace.video!.id, trimmedQuery);
      let citedTranscripts = workspace.transcripts;
      if (agentMode === "knowledgeBase" && answer.videoId > 0 && answer.videoId !== workspace.video?.id) {
        const citedDetail = await getVideoDetail(answer.videoId);
        citedTranscripts = citedDetail.transcripts;
        setWorkspace({
          video: citedDetail.video,
          job: citedDetail.job,
          transcripts: citedDetail.transcripts,
          summaries: citedDetail.summaries,
          deduplicated: true,
        });
        resetTranscriptSearch();
      }
      setMessages((current) => [
        ...current,
        {
          role: "agent",
          text: answer.answer,
          citation: answer.citation,
          citations: answer.citations,
          confidenceScore: answer.confidenceScore,
          confidenceLevel: answer.confidenceLevel,
          contextUsed: answer.contextUsed,
          cacheHit: answer.cacheHit,
          answerMode: answer.answerMode,
          trace: answer.trace,
        },
      ]);
      const contextVideoId = answer.videoId > 0 ? answer.videoId : workspace.video?.id;
      if (contextVideoId) {
        setAgentContext(await getAgentContext(contextVideoId));
      }
      refreshRuntimeStatus();

      const citationIndex = citedTranscripts.findIndex(
        (segment) => segment.startMs === answer.startMs,
      );
      if (citationIndex >= 0) {
        setActiveSegment(citationIndex);
      }
    } catch (exception) {
      setMessages((current) => [
        ...current,
        {
          role: "agent",
          text: exception instanceof Error ? exception.message : "Agent 请求失败",
        },
      ]);
    }
  }

  async function handleTranscriptSearch(keyword: string) {
    const trimmedKeyword = keyword.trim();
    if (!workspace.video || !trimmedKeyword) {
      setTranscriptSearchResults([]);
      return;
    }

    setIsSearchingTranscripts(true);
    setError("");
    try {
      setTranscriptSearchResults(await searchTranscripts(workspace.video.id, trimmedKeyword));
    } catch (exception) {
      setError(exception instanceof Error ? exception.message : "字幕搜索失败");
    } finally {
      setIsSearchingTranscripts(false);
    }
  }

  function handleTranscriptSearchQueryChange(keyword: string) {
    setTranscriptSearchQuery(keyword);
    if (!keyword.trim()) {
      setTranscriptSearchResults([]);
    }
  }

  function handleSelectSearchResult(segment: ApiTranscriptSegment) {
    const index = workspace.transcripts.findIndex((item) => item.id === segment.id);
    if (index >= 0) {
      handleSelectTranscript(index);
    }
  }

  function handleSelectTranscript(index: number) {
    const segment = workspace.transcripts[index];
    setActiveSegment(index);
    if (segment && videoRef.current) {
      videoRef.current.currentTime = segment.startMs / 1000;
      videoRef.current.play().catch(() => undefined);
    }
  }

  async function handleSelectCitation(citation: AgentCitation) {
    setError("");
    let targetTranscripts = workspace.transcripts;
    if (citation.videoId > 0 && citation.videoId !== workspace.video?.id) {
      const detail = await getVideoDetail(citation.videoId);
      targetTranscripts = detail.transcripts;
      setWorkspace({
        video: detail.video,
        job: detail.job,
        transcripts: detail.transcripts,
        summaries: detail.summaries,
        deduplicated: true,
      });
      resetTranscriptSearch();
    }

    const index = targetTranscripts.findIndex(
      (segment) => segment.id === citation.segmentId || segment.startMs === citation.startMs,
    );
    if (index >= 0) {
      setActiveSegment(index);
      setPlaybackMs(citation.startMs);
      window.setTimeout(() => {
        if (videoRef.current) {
          videoRef.current.currentTime = citation.startMs / 1000;
          videoRef.current.play().catch(() => undefined);
        }
      }, 0);
    }
  }

  async function handleClearAgentMessages() {
    if (!workspace.video) return;
    setError("");
    try {
      await clearAgentMessages(workspace.video.id);
      setAgentContext(await getAgentContext(workspace.video.id));
      setMessages([
        buildWorkspaceIntro(`已清空 ${workspace.video.originalName} 的 Agent 问答历史。`),
      ]);
    } catch (exception) {
      setError(exception instanceof Error ? exception.message : "清空 Agent 历史失败");
    }
  }

  function handlePlaybackTimeChange(currentTimeSeconds: number) {
    const currentMs = Math.floor(currentTimeSeconds * 1000);
    setPlaybackMs(currentMs);
    const nextIndex = findTranscriptIndexAtMs(workspace.transcripts, currentMs);
    if (nextIndex >= 0 && nextIndex !== activeSegment) {
      setActiveSegment(nextIndex);
    }
  }

  return (
    <main className="app-shell">
      <Header />
      <section className="workspace-grid">
        <aside className="left-rail" aria-label="上传与任务">
          <UploadPanel
            deduplicated={workspace.deduplicated}
            error={error}
            isLoading={isLoading}
            job={workspace.job}
            onImportUrl={handleImportUrl}
            onRetry={handleRetryVideo}
            onUpload={handleUpload}
            summariesCount={workspace.summaries.length}
            transcriptsCount={workspace.transcripts.length}
            options={urlImportOptions}
            urlValue={videoUrl}
            onOptionsChange={setUrlImportOptions}
            onUrlChange={setVideoUrl}
            video={workspace.video}
          />
          <PipelinePanel steps={steps} />
        </aside>

        <section className="center-stage" aria-label="视频与字幕">
          <VideoPanel
            activeTranscript={activeTranscript}
            onPlaybackTimeChange={handlePlaybackTimeChange}
            playbackMs={playbackMs}
            video={workspace.video}
            videoRef={videoRef}
          />
          <TranscriptPanel
            activeSegment={activeSegment}
            onSelect={handleSelectTranscript}
            onSearch={handleTranscriptSearch}
            onSearchQueryChange={handleTranscriptSearchQueryChange}
            onSelectSearchResult={handleSelectSearchResult}
            searchQuery={transcriptSearchQuery}
            searchResults={transcriptSearchResults}
            searching={isSearchingTranscripts}
            transcripts={workspace.transcripts}
          />
        </section>

        <aside className="right-rail" aria-label="总结与问答">
          <HeaderActions
            diagnosticsOpen={diagnosticsOpen}
            embeddingOpen={embeddingOpen}
            embeddingProvider={runtimeStatus?.llm.embeddingProvider}
            libraryOpen={libraryOpen}
            llmConfig={llmConfig}
            llmOpen={llmOpen}
            onDiagnosticsToggle={() => {
              setDiagnosticsOpen((current) => !current);
              setLlmOpen(false);
              setEmbeddingOpen(false);
              setLibraryOpen(false);
            }}
            onEmbeddingToggle={() => {
              setEmbeddingOpen((current) => !current);
              setLlmOpen(false);
              setDiagnosticsOpen(false);
              setLibraryOpen(false);
            }}
            onLibraryToggle={() => {
              setLibraryOpen((current) => !current);
              setLlmOpen(false);
              setEmbeddingOpen(false);
              setDiagnosticsOpen(false);
            }}
            onLlmToggle={() => {
              setLlmOpen((current) => !current);
              setDiagnosticsOpen(false);
              setEmbeddingOpen(false);
              setLibraryOpen(false);
            }}
            videosCount={videos.length}
          />
          {llmOpen ? (
            <LlmConfigPanel
              activatingProviderId={activatingLlmId}
              config={llmConfig}
              form={llmForm}
              isSaving={isSavingLlm}
              isTesting={isTestingLlm}
              onActivateProvider={handleActivateLlmProvider}
              onChange={setLlmForm}
              onClose={() => setLlmOpen(false)}
              onSave={handleSaveLlmConfig}
              onTest={handleTestLlmConnection}
              providers={llmProviders}
              status={llmStatus}
            />
          ) : embeddingOpen ? (
            <EmbeddingConfigPanel
              activatingProviderId={activatingEmbeddingId}
              form={embeddingForm}
              isSaving={isSavingEmbedding}
              isTesting={isTestingEmbedding}
              onActivateProvider={handleActivateEmbeddingProvider}
              onChange={setEmbeddingForm}
              onClose={() => setEmbeddingOpen(false)}
              onModeChange={handleEmbeddingModeChange}
              onSave={handleSaveEmbeddingProvider}
              onTest={handleTestEmbeddingConnection}
              providers={embeddingProviders}
              runtimeStatus={runtimeStatus}
              status={embeddingStatus}
            />
          ) : diagnosticsOpen ? (
            <DiagnosticsPanel
              activeTab={diagnosticsTab}
              agentContext={agentContext}
              asrDiagnostic={asrDiagnostic}
              failedJobs={failedJobs}
              isLoading={isLoading}
              isEvaluatingOcr={isEvaluatingOcr}
              isFusingOcr={isFusingOcr}
              isAligningOcr={isAligningOcr}
              isRefiningLowConfidence={isRefiningLowConfidence}
              isRepairingText={isRepairingText}
              isSavingTermGlossary={isSavingTermGlossary}
              isRebuildingVectorIndex={isRebuildingVectorIndex}
              job={workspace.job}
              latestAgentMessage={latestAgentMessage}
              mysqlExplain={mysqlExplain}
              onClose={() => setDiagnosticsOpen(false)}
              onAddTermGlossary={handleAddTermGlossary}
              onAlignOcr={handleAlignOcr}
              onDeleteTermGlossary={handleDeleteTermGlossary}
              onEvaluateOcr={handleEvaluateOcr}
              onFuseOcr={handleFuseOcr}
              onRepairTranscriptText={handleRepairTranscriptText}
              onRebuildVectorIndex={handleRebuildVectorIndex}
              onRefreshAsr={() => refreshAsrDiagnostic()}
              onRefreshFailedJobs={refreshFailedJobs}
              onRefreshMysql={refreshMysqlExplain}
              onRefreshRedis={refreshRedisInspect}
              onRefreshTermGlossary={refreshTermGlossary}
              onRefreshThreadPool={refreshThreadPoolInspect}
              onRefreshVectorStore={refreshVectorIndexInspect}
              onRefineLowConfidence={handleRefineLowConfidence}
              onReprocessAsr={handleReprocessAsr}
              onRetryFailedJob={handleRetryFailedJob}
              onSelectFailedVideo={loadVideo}
              onTabChange={setDiagnosticsTab}
              onTermGlossaryFormChange={setTermGlossaryForm}
              onToggleTermGlossary={handleToggleTermGlossary}
              ocrQuality={ocrQuality}
              ocrStatus={ocrStatus}
              pendingTermGlossaryId={pendingTermGlossaryId}
              rebuildStatus={vectorIndexStatus}
              redisInspect={redisInspect}
              runtimeStatus={runtimeStatus}
              sseInspect={sseInspect}
              termGlossary={termGlossary}
              termGlossaryForm={termGlossaryForm}
              termGlossaryStatus={termGlossaryStatus}
              textRepairStatus={textRepairStatus}
              summaries={workspace.summaries}
              threadPoolInspect={threadPoolInspect}
              transcripts={workspace.transcripts}
              vectorIndexInspect={vectorIndexInspect}
              video={workspace.video}
            />
          ) : libraryOpen ? (
            <VideoLibraryPanel
              activeVideoId={workspace.video?.id ?? null}
              onClose={() => setLibraryOpen(false)}
              onSelect={(videoId) => {
                setLibraryOpen(false);
                loadVideo(videoId);
              }}
              videos={videos}
              variant="popover"
            />
          ) : (
            <RightWorkspacePanel
              activeTab={rightWorkspaceTab}
              agent={
                <AgentPanel
                  activeKnowledgeBaseDetail={activeKnowledgeBaseDetail}
                  activeKnowledgeBaseId={activeKnowledgeBaseId}
                  context={agentContext}
                  disabled={agentMode === "video" && !workspace.video}
                  isSavingKnowledgeBase={isSavingKnowledgeBase}
                  knowledgeBaseForm={knowledgeBaseForm}
                  knowledgeBases={knowledgeBases}
                  knowledgeBaseStatus={knowledgeBaseStatus}
                  messages={messages}
                  mode={agentMode}
                  onAddVideoToKnowledgeBase={handleAddVideoToKnowledgeBase}
                  onAsk={handleAskAgent}
                  onClear={handleClearAgentMessages}
                  onCitationSelect={handleSelectCitation}
                  onCreateKnowledgeBase={handleCreateKnowledgeBase}
                  onDeleteKnowledgeBase={handleDeleteKnowledgeBase}
                  onKnowledgeBaseFormChange={setKnowledgeBaseForm}
                  onModeChange={setAgentMode}
                  onQueryChange={setQuery}
                  onRemoveVideoFromKnowledgeBase={handleRemoveVideoFromKnowledgeBase}
                  onSelectKnowledgeBase={handleSelectKnowledgeBase}
                  pendingKnowledgeBaseVideoId={pendingKnowledgeBaseVideoId}
                  query={query}
                  video={workspace.video}
                  videos={videos}
                />
              }
              onTabChange={setRightWorkspaceTab}
              summariesCount={workspace.summaries.length}
              summary={<SummaryPanel summaries={workspace.summaries} />}
            />
          )}
        </aside>
      </section>
    </main>
  );
}

function DiagnosticsPanel({
  activeTab,
  agentContext,
  asrDiagnostic,
  failedJobs,
  isAligningOcr,
  isEvaluatingOcr,
  isFusingOcr,
  isRefiningLowConfidence,
  isRepairingText,
  isLoading,
  isSavingTermGlossary,
  isRebuildingVectorIndex,
  job,
  latestAgentMessage,
  mysqlExplain,
  onAddTermGlossary,
  onAlignOcr,
  onDeleteTermGlossary,
  onEvaluateOcr,
  onFuseOcr,
  onRepairTranscriptText,
  onRebuildVectorIndex,
  onClose,
  onRefreshAsr,
  onRefreshFailedJobs,
  onRefreshMysql,
  onRefreshRedis,
  onRefreshTermGlossary,
  onRefreshThreadPool,
  onRefreshVectorStore,
  onRefineLowConfidence,
  onReprocessAsr,
  onRetryFailedJob,
  onSelectFailedVideo,
  onTabChange,
  onTermGlossaryFormChange,
  onToggleTermGlossary,
  ocrQuality,
  ocrStatus,
  pendingTermGlossaryId,
  rebuildStatus,
  redisInspect,
  runtimeStatus,
  sseInspect,
  termGlossary,
  termGlossaryForm,
  termGlossaryStatus,
  textRepairStatus,
  summaries,
  threadPoolInspect,
  transcripts,
  vectorIndexInspect,
  video,
}: {
  activeTab: DiagnosticsTab;
  agentContext: AgentContext | null;
  asrDiagnostic: AsrDiagnostic | null;
  failedJobs: FailedJob[];
  isAligningOcr: boolean;
  isEvaluatingOcr: boolean;
  isFusingOcr: boolean;
  isRefiningLowConfidence: boolean;
  isRepairingText: boolean;
  isLoading: boolean;
  isSavingTermGlossary: boolean;
  isRebuildingVectorIndex: boolean;
  job: ProcessingJob | null;
  latestAgentMessage: ChatMessage | undefined;
  mysqlExplain: MysqlExplainPlan[];
  onAddTermGlossary: () => void;
  onAlignOcr: () => void;
  onDeleteTermGlossary: (entryId: number) => void;
  onEvaluateOcr: () => void;
  onFuseOcr: () => void;
  onRepairTranscriptText: () => void;
  onRebuildVectorIndex: () => void;
  onClose: () => void;
  onRefreshAsr: () => void;
  onRefreshFailedJobs: () => void;
  onRefreshMysql: () => void;
  onRefreshRedis: () => void;
  onRefreshTermGlossary: () => void;
  onRefreshThreadPool: () => void;
  onRefreshVectorStore: () => void;
  onRefineLowConfidence: () => void;
  onReprocessAsr: () => void;
  onRetryFailedJob: (videoId: number) => void;
  onSelectFailedVideo: (videoId: number) => void;
  onTabChange: (tab: DiagnosticsTab) => void;
  onTermGlossaryFormChange: (form: TermGlossaryFormState) => void;
  onToggleTermGlossary: (entry: TermGlossaryEntry) => void;
  ocrQuality: OcrSubtitleQuality | null;
  ocrStatus: string;
  pendingTermGlossaryId: number | null;
  rebuildStatus: string;
  redisInspect: RedisInspectResponse | null;
  runtimeStatus: RuntimeStatus | null;
  sseInspect: SseInspectState;
  termGlossary: TermGlossaryEntry[];
  termGlossaryForm: TermGlossaryFormState;
  termGlossaryStatus: string;
  textRepairStatus: string;
  summaries: SummaryAsset[];
  threadPoolInspect: ThreadPoolInspectResponse | null;
  transcripts: ApiTranscriptSegment[];
  vectorIndexInspect: VectorIndexStatusResponse | null;
  video: VideoAsset | null;
}) {
  return (
    <section className="diagnostics-panel" aria-label="面试钩子诊断台">
      <div className="diagnostics-head">
        <div className="diagnostics-title-row">
          <div>
            <div className="eyebrow">
              <ShieldCheck size={16} />
              Interview Hooks
            </div>
            <h2>诊断台</h2>
          </div>
          <button className="diagnostics-close" onClick={onClose} type="button">
            关闭
          </button>
        </div>
        <div>
          <p>把 MySQL、Redis、RAG、ASR、线程池和补偿链路收纳到这里，主流程保持清爽。</p>
        </div>
        <div className="diagnostics-metrics" aria-label="诊断状态指标">
          <Metric icon={<Database size={16} />} label="MySQL 状态机" value="6 tables" />
          <Metric icon={<Zap size={16} />} label="Redis 钩子" value="7 keys" />
          <Metric icon={<Bot size={16} />} label="Agent 工具" value="3 tools" />
        </div>
        <div className="diagnostics-tabs" role="tablist" aria-label="诊断分类">
          {diagnosticTabs.map((tab) => (
            <button
              aria-selected={activeTab === tab.id}
              className={activeTab === tab.id ? "active" : ""}
              key={tab.id}
              onClick={() => onTabChange(tab.id)}
              role="tab"
              type="button"
            >
              <strong>{tab.label}</strong>
              <small>{tab.meta}</small>
            </button>
          ))}
        </div>
      </div>

      <div className={`diagnostics-grid ${activeTab}`}>
        {activeTab === "runtime" && (
          <>
            <RuntimeStatusPanel
              isRebuildingVectorIndex={isRebuildingVectorIndex}
              onRebuildVectorIndex={onRebuildVectorIndex}
              rebuildStatus={rebuildStatus}
              status={runtimeStatus}
            />
            <ThreadPoolInspectorPanel
              inspect={threadPoolInspect}
              onRefresh={onRefreshThreadPool}
            />
            <SseProgressInspectorPanel inspect={sseInspect} />
          </>
        )}

        {activeTab === "ai" && (
          <>
            <VectorStoreInspectorPanel
              inspect={vectorIndexInspect}
              onRefresh={onRefreshVectorStore}
            />
            <RetrievalInspectorPanel
              latestMessage={latestAgentMessage}
              status={runtimeStatus}
            />
          </>
        )}

        {activeTab === "data" && (
          <>
            <MysqlIndexInspectorPanel
              plans={mysqlExplain}
              onRefresh={onRefreshMysql}
            />
            <RedisKeyInspectorPanel
              inspect={redisInspect}
              onRefresh={onRefreshRedis}
            />
            <RedisHooksPanel
              context={agentContext}
              latestMessage={latestAgentMessage}
              status={runtimeStatus}
            />
            <DatabaseStatePanel
              job={job}
              runtime={runtimeStatus}
              summaries={summaries}
              transcripts={transcripts}
              video={video}
            />
          </>
        )}

        {activeTab === "recovery" && (
          <>
            <AsrDiagnosticPanel
              diagnostic={asrDiagnostic}
              isAligningOcr={isAligningOcr}
              isEvaluatingOcr={isEvaluatingOcr}
              isFusingOcr={isFusingOcr}
              isRefiningLowConfidence={isRefiningLowConfidence}
              isRepairingText={isRepairingText}
              ocrQuality={ocrQuality}
              ocrStatus={ocrStatus}
              onAlignOcr={onAlignOcr}
              onEvaluateOcr={onEvaluateOcr}
              onFuseOcr={onFuseOcr}
              onRefineLowConfidence={onRefineLowConfidence}
              onRepairText={onRepairTranscriptText}
              onRefresh={onRefreshAsr}
              onReprocess={onReprocessAsr}
              textRepairStatus={textRepairStatus}
              video={video}
            />
            <TermGlossaryPanel
              entries={termGlossary}
              form={termGlossaryForm}
              isSaving={isSavingTermGlossary}
              onAdd={onAddTermGlossary}
              onChange={onTermGlossaryFormChange}
              onDelete={onDeleteTermGlossary}
              onRefresh={onRefreshTermGlossary}
              onToggle={onToggleTermGlossary}
              pendingEntryId={pendingTermGlossaryId}
              status={termGlossaryStatus}
            />
            <RecoveryPanel
              failedJobs={failedJobs}
              isLoading={isLoading}
              onRefresh={onRefreshFailedJobs}
              onRetry={onRetryFailedJob}
              onSelect={onSelectFailedVideo}
            />
            <HookPanel
              job={job}
              summaries={summaries}
              transcripts={transcripts}
              video={video}
            />
          </>
        )}
      </div>
    </section>
  );
}

function buildPipelineSteps(job: ProcessingJob | null): PipelineStep[] {
  if (!job) {
    return [
      {
        label: "等待上传",
        detail: "选择文件或粘贴平台 URL",
        status: "waiting",
      },
      {
        label: "MD5 去重",
        detail: "后端返回 deduplicated 状态",
        status: "waiting",
      },
      {
        label: "任务状态机",
        detail: "读取 processing_job",
        status: "waiting",
      },
      {
        label: "字幕与总结",
        detail: "读取 transcripts / summaries",
        status: "waiting",
      },
      {
        label: "Agent 引用",
        detail: "调用 /agent/ask",
        status: "waiting",
      },
    ];
  }

  const done = job.status === "DONE";
  return [
    {
      label: "MD5 去重",
      detail: "Redis/Local lock + MySQL uk_video_md5",
      status: "done",
    },
    {
      label: "任务入库",
      detail: `job#${job.id} 已创建`,
      status: "done",
    },
    {
      label: done ? "本地 DAG 完成" : "本地 DAG 运行中",
      detail: `${job.currentStep} · ${job.progress}%`,
      status: done ? "done" : "running",
    },
    {
      label: "字幕与总结",
      detail: "后端已返回字幕片段和总结资产",
      status: done ? "done" : "waiting",
    },
    {
      label: "Agent 引用",
      detail: "可调用视频问答接口",
      status: done ? "running" : "waiting",
    },
  ];
}

function formatTime(ms: number) {
  const totalSeconds = Math.floor(ms / 1000);
  const minutes = Math.floor(totalSeconds / 60).toString().padStart(2, "0");
  const seconds = (totalSeconds % 60).toString().padStart(2, "0");
  return `${minutes}:${seconds}`;
}

function formatDuration(ms: number) {
  if (ms <= 0) return "时长待识别";
  const totalSeconds = Math.round(ms / 1000);
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60).toString().padStart(hours ? 2 : 1, "0");
  const seconds = (totalSeconds % 60).toString().padStart(2, "0");
  return hours ? `${hours}:${minutes}:${seconds}` : `${minutes}:${seconds}`;
}

function formatPercent(value: number) {
  if (!Number.isFinite(value)) return "0%";
  return `${Math.round(value * 1000) / 10}%`;
}

function buildHistoryCitation(
  videoId: number,
  citationText: string | undefined,
  transcripts: ApiTranscriptSegment[],
) {
  if (!citationText) return undefined;
  const timeRange = parseTimeRangeMs(citationText);
  if (!timeRange) return undefined;
  const index = findTranscriptIndexAtMs(transcripts, timeRange.startMs);
  const segment = index >= 0 ? transcripts[index] : undefined;
  if (!segment) return undefined;
  return [
    {
      citation: citationText,
      videoId,
      segmentId: segment.id,
      startMs: segment.startMs,
      endMs: segment.endMs || timeRange.endMs,
      score: 0,
      snippet: segment.content,
    },
  ];
}

function parseTimeRangeMs(citationText: string) {
  const match = citationText.match(/(\d{1,2}):(\d{2})-(\d{1,2}):(\d{2})/);
  if (!match) return null;
  const startMs = (Number(match[1]) * 60 + Number(match[2])) * 1000;
  const endMs = (Number(match[3]) * 60 + Number(match[4])) * 1000;
  return { startMs, endMs };
}

function findTranscriptIndexAtMs(transcripts: ApiTranscriptSegment[], currentMs: number) {
  if (transcripts.length === 0) return -1;
  const exactIndex = transcripts.findIndex(
    (segment) => currentMs >= segment.startMs && currentMs < segment.endMs,
  );
  if (exactIndex >= 0) return exactIndex;

  for (let index = transcripts.length - 1; index >= 0; index--) {
    if (currentMs >= transcripts[index].startMs) {
      return index;
    }
  }
  return 0;
}

function parseSummary(summary: SummaryAsset | undefined) {
  if (!summary) return [];
  try {
    const parsed = JSON.parse(summary.contentJson) as {
      hooks?: string[];
      items?: string[];
      points?: string[];
    };
    return parsed.points ?? parsed.hooks ?? parsed.items ?? [];
  } catch {
    return [summary.contentJson];
  }
}

const summaryTemplates = [
  { type: "CORE_POINTS", label: "核心观点", actionLabel: "生成核心观点总结" },
  { type: "MEETING_MINUTES", label: "会议纪要", actionLabel: "生成会议总结" },
  { type: "BLOG_OUTLINE", label: "博客大纲", actionLabel: "生成博客大纲" },
  { type: "PPT_OUTLINE", label: "PPT 大纲", actionLabel: "生成 PPT 大纲" },
];

const diagnosticTabs: { id: DiagnosticsTab; label: string; meta: string }[] = [
  { id: "runtime", label: "Runtime", meta: "线程 / SSE" },
  { id: "ai", label: "AI/RAG", meta: "向量 / 检索" },
  { id: "data", label: "Data", meta: "MySQL / Redis" },
  { id: "recovery", label: "Recovery", meta: "ASR / 补偿" },
];

const embeddingDefaults: Record<EmbeddingMode, Pick<EmbeddingFormState, "providerName" | "baseUrl" | "model">> = {
  qwen: {
    providerName: "Qwen Embedding",
    baseUrl: "https://dashscope.aliyuncs.com/compatible-mode/v1",
    model: "text-embedding-v4",
  },
  openai: {
    providerName: "OpenAI Embedding",
    baseUrl: "https://api.openai.com/v1",
    model: "text-embedding-3-small",
  },
  bge: {
    providerName: "BGE Embedding",
    baseUrl: "http://localhost:8000/v1",
    model: "BAAI/bge-m3",
  },
};

function Header() {
  return (
    <header className="app-header">
      <div>
        <div className="eyebrow">
          <BrainCircuit size={16} />
          Java 后端求职型 AI Agent 项目
        </div>
        <h1>OmniVid 工作台</h1>
      </div>
    </header>
  );
}

function HeaderActions({
  diagnosticsOpen,
  embeddingOpen,
  embeddingProvider,
  libraryOpen,
  llmConfig,
  llmOpen,
  onDiagnosticsToggle,
  onEmbeddingToggle,
  onLibraryToggle,
  onLlmToggle,
  videosCount,
}: {
  diagnosticsOpen: boolean;
  embeddingOpen: boolean;
  embeddingProvider?: string;
  libraryOpen: boolean;
  llmConfig: LlmConfig | null;
  llmOpen: boolean;
  onDiagnosticsToggle: () => void;
  onEmbeddingToggle: () => void;
  onLibraryToggle: () => void;
  onLlmToggle: () => void;
  videosCount: number;
}) {
  const llmReady = Boolean(llmConfig?.enabled && llmConfig.configured);
  const embeddingReady = Boolean(embeddingProvider && !embeddingProvider.includes("local"));

  return (
    <div className="header-metrics" aria-label="绯荤粺鎸囨爣">
      <HeaderLlmButton
        active={llmOpen}
        ready={llmReady}
        model={llmConfig?.model}
        onClick={onLlmToggle}
      />
      <HeaderEmbeddingButton
        active={embeddingOpen}
        provider={embeddingProvider}
        ready={embeddingReady}
        onClick={onEmbeddingToggle}
      />
      <HeaderDiagnosticsButton
        active={diagnosticsOpen}
        onClick={onDiagnosticsToggle}
      />
      <HeaderLibraryButton
        active={libraryOpen}
        count={videosCount}
        onClick={onLibraryToggle}
      />
    </div>
  );
}

function HeaderEmbeddingButton({
  active,
  onClick,
  provider,
  ready,
}: {
  active: boolean;
  onClick: () => void;
  provider?: string;
  ready: boolean;
}) {
  return (
    <button
      aria-expanded={active}
      className={`metric top-popover-trigger llm-trigger ${active ? "active" : ""} ${ready ? "ready" : ""}`}
      onClick={onClick}
      type="button"
    >
      <span className="metric-icon">
        <Fingerprint size={17} />
      </span>
      <span>
        <strong>Embedding</strong>
        <small>{ready ? provider ?? "已接通" : "配置向量模型"}</small>
      </span>
    </button>
  );
}

function HeaderLibraryButton({
  active,
  count,
  onClick,
}: {
  active: boolean;
  count: number;
  onClick: () => void;
}) {
  return (
    <button
      aria-expanded={active}
      className={`metric top-popover-trigger library-trigger ${active ? "active" : ""}`}
      onClick={onClick}
      type="button"
    >
      <span className="metric-icon">
        <Database size={17} />
      </span>
      <span>
        <strong>视频库</strong>
        <small>{count ? `${count} 个本地视频` : "等待上传"}</small>
      </span>
    </button>
  );
}

function HeaderLlmButton({
  active,
  model,
  onClick,
  ready,
}: {
  active: boolean;
  model?: string;
  onClick: () => void;
  ready: boolean;
}) {
  return (
    <button
      aria-expanded={active}
      className={`metric top-popover-trigger llm-trigger ${active ? "active" : ""} ${ready ? "ready" : ""}`}
      onClick={onClick}
      type="button"
    >
      <span className="metric-icon">
        <KeyRound size={17} />
      </span>
      <span>
        <strong>云端 LLM</strong>
        <small>{ready ? model ?? "已配置" : "添加 API Key"}</small>
      </span>
    </button>
  );
}

function HeaderDiagnosticsButton({
  active,
  onClick,
}: {
  active: boolean;
  onClick: () => void;
}) {
  return (
    <button
      aria-expanded={active}
      className={`metric top-popover-trigger diagnostics-trigger ${active ? "active" : ""}`}
      onClick={onClick}
      type="button"
    >
      <span className="metric-icon">
        <ShieldCheck size={17} />
      </span>
      <span>
        <strong>诊断台</strong>
        <small>{active ? "点击收起" : "点击查看"}</small>
      </span>
    </button>
  );
}

function Metric({
  icon,
  label,
  value,
}: {
  icon: React.ReactNode;
  label: string;
  value: string;
}) {
  return (
    <div className="metric">
      <span className="metric-icon">{icon}</span>
      <span>
        <strong>{value}</strong>
        <small>{label}</small>
      </span>
    </div>
  );
}

function UploadPanel({
  deduplicated,
  error,
  isLoading,
  job,
  onImportUrl,
  onOptionsChange,
  onRetry,
  onUpload,
  onUrlChange,
  options,
  summariesCount,
  transcriptsCount,
  urlValue,
  video,
}: {
  deduplicated: boolean | null;
  error: string;
  isLoading: boolean;
  job: ProcessingJob | null;
  onImportUrl: (url: string) => void;
  onOptionsChange: (value: UrlImportOptions) => void;
  onRetry: () => void;
  onUpload: (file: File) => void;
  onUrlChange: (value: string) => void;
  options: UrlImportOptions;
  summariesCount: number;
  transcriptsCount: number;
  urlValue: string;
  video: VideoAsset | null;
}) {
  const progress = isLoading ? 8 : job?.progress ?? 0;
  const statusTone = isLoading
    ? "running"
    : job?.status === "DONE"
      ? "done"
      : job?.status === "FAILED"
        ? "failed"
        : job?.status === "RUNNING"
          ? "running"
          : "waiting";
  const statusLabel = isLoading
    ? "上传中"
    : job?.status === "DONE"
      ? "解析完成"
      : job?.status === "FAILED"
        ? "解析失败"
        : job?.status === "RUNNING"
          ? "解析中"
          : "等待上传";
  const stepLabel = isLoading ? "UPLOAD_STREAMING" : job?.currentStep ?? "NO_JOB";
  const canRetry = Boolean(video && job?.status === "FAILED" && !isLoading);
  const statusText = video
    ? deduplicated
      ? "后端命中去重"
      : "后端创建完成"
    : "选择本地视频";

  return (
    <section className="panel upload-panel">
      <div className="panel-title">
        <UploadCloud size={19} />
        <h2>视频上传</h2>
      </div>
      <label className={`upload-drop ${isLoading ? "disabled" : ""}`}>
        <input
          accept="video/*"
          className="file-input"
          disabled={isLoading}
          onChange={(event) => {
            const file = event.currentTarget.files?.[0];
            event.currentTarget.value = "";
            if (file) {
              onUpload(file);
            }
          }}
          type="file"
        />
        {video ? <CheckCircle2 size={28} /> : <Video size={28} />}
        <span>{isLoading ? "正在上传到后端" : statusText}</span>
        <small>
          {video
            ? `${video.originalName} · ${formatDuration(video.durationMs)} · job ${job?.status ?? "PENDING"}`
            : "POST /api/videos/upload/file"}
        </small>
      </label>
      <form
        className="url-import-form"
        onSubmit={(event) => {
          event.preventDefault();
          onImportUrl(urlValue);
        }}
      >
        <Link2 size={16} />
        <input
          disabled={isLoading}
          onChange={(event) => onUrlChange(event.target.value)}
          placeholder="粘贴 B站 / 抖音 / 小红书链接"
          value={urlValue}
        />
        <button disabled={isLoading || !urlValue.trim()} type="submit">
          解析
        </button>
      </form>
      <div className="url-import-options">
        <label>
          <span>cookies.txt</span>
          <input
            disabled={isLoading}
            onChange={(event) => onOptionsChange({ ...options, cookiesFile: event.currentTarget.value })}
            placeholder="E:/cookies/bilibili.txt"
            value={options.cookiesFile}
          />
        </label>
        <label>
          <span>browser cookies</span>
          <select
            disabled={isLoading || Boolean(options.cookiesFile.trim())}
            onChange={(event) => onOptionsChange({ ...options, cookiesFromBrowser: event.currentTarget.value })}
            value={options.cookiesFromBrowser}
          >
            <option value="none">none</option>
            <option value="edge">edge</option>
            <option value="chrome">chrome</option>
            <option value="firefox">firefox</option>
          </select>
        </label>
      </div>
      <div className="dedupe-card">
        <Fingerprint size={18} />
        <div>
          <strong>
            {deduplicated === null
              ? "等待防重结果"
              : deduplicated
                ? "deduplicated=true"
                : "deduplicated=false"}
          </strong>
          <span>
            {video
              ? `MD5 ${video.md5.slice(0, 8)}...${video.md5.slice(-4)}`
              : "后端会用锁服务 + MySQL 唯一索引处理重复上传"}
          </span>
        </div>
      </div>
      <div className={`upload-status-card ${statusTone}`}>
        <div className="status-card-head">
          <span>{statusLabel}</span>
          <strong>{Math.min(100, progress)}%</strong>
        </div>
        <div className="status-progress" aria-label="任务进度">
          <span style={{ width: `${Math.min(100, progress)}%` }} />
        </div>
        <div className="status-card-grid">
          <span>
            <strong>{stepLabel}</strong>
            <small>当前阶段</small>
          </span>
          <span>
            <strong>{transcriptsCount}</strong>
            <small>字幕</small>
          </span>
          <span>
            <strong>{summariesCount}</strong>
            <small>总结</small>
          </span>
        </div>
      </div>
      {job?.status === "FAILED" && (
        <div className="failure-diagnostic">
          <div>
            <span>FAILED JOB</span>
            <strong>job#{job.id} / retry {job.retryCount ?? 0}</strong>
          </div>
          <small>{job.errorMessage || "No backend error message captured"}</small>
        </div>
      )}
      {canRetry && (
        <button className="text-button retry-job-button" onClick={onRetry} type="button">
          <GitBranch size={15} />
          重试解析
        </button>
      )}
      {error && <p className="inline-error">{error}</p>}
    </section>
  );
}

function LlmConfigPanel({
  activatingProviderId,
  config,
  form,
  isSaving,
  isTesting,
  onActivateProvider,
  onChange,
  onClose,
  onSave,
  onTest,
  providers,
  status,
}: {
  activatingProviderId: number | null;
  config: LlmConfig | null;
  form: LlmFormState;
  isSaving: boolean;
  isTesting: boolean;
  onActivateProvider: (providerId: number) => void;
  onChange: (next: LlmFormState) => void;
  onClose: () => void;
  onSave: () => void;
  onTest: () => void;
  providers: LlmProvider[];
  status: string;
}) {
  const ready = Boolean(config?.enabled && config.configured);
  const stateLabel = ready
    ? `已配置 ${config?.model ?? form.model}`
    : config?.enabled
      ? "等待 Key"
      : "未启用";
  const [isEditing, setIsEditing] = useState(false);
  const showEditor = !ready || isEditing;

  return (
    <section className="panel llm-panel">
      <div className="panel-title">
        <KeyRound size={19} />
        <h2>云端 LLM</h2>
        <span className={`panel-count ${ready ? "ready" : ""}`}>{stateLabel}</span>
        <button className="llm-close" onClick={onClose} type="button">
          关闭
        </button>
        {ready && (
          <button className="panel-action" onClick={() => setIsEditing((current) => !current)} type="button">
            {showEditor ? "收起" : "配置"}
          </button>
        )}
      </div>
      {showEditor && (
        <>
          <div className="llm-switch-row">
            <label>
              <input
                checked={form.enabled}
                onChange={(event) => onChange({ ...form, enabled: event.currentTarget.checked })}
                type="checkbox"
              />
              <span>启用</span>
            </label>
            <small>{config?.apiKeyMasked || "未保存 Key"}</small>
          </div>
          <div className="llm-fields">
            <label>
              <span>名称</span>
              <input
                onChange={(event) => onChange({ ...form, providerName: event.currentTarget.value })}
                placeholder="DeepSeek"
                value={form.providerName}
              />
            </label>
            <label>
              <span>Base URL</span>
              <input
                onChange={(event) => onChange({ ...form, baseUrl: event.currentTarget.value })}
                placeholder="https://api.deepseek.com/v1"
                value={form.baseUrl}
              />
            </label>
            <label>
              <span>模型</span>
              <input
                onChange={(event) => onChange({ ...form, model: event.currentTarget.value })}
                placeholder="deepseek-chat"
                value={form.model}
              />
            </label>
            <label>
              <span>API Key</span>
              <input
                autoComplete="off"
                onChange={(event) => onChange({ ...form, apiKey: event.currentTarget.value })}
                placeholder={config?.configured ? config.apiKeyMasked : "sk-..."}
                type="password"
                value={form.apiKey}
              />
            </label>
            <label>
              <span>超时</span>
              <input
                min={5}
                max={180}
                onChange={(event) => onChange({ ...form, timeoutSeconds: Number(event.currentTarget.value) })}
                type="number"
                value={form.timeoutSeconds}
              />
            </label>
          </div>
          <div className="llm-actions">
            <button disabled={isSaving} onClick={onSave} type="button">
              {isSaving ? "保存中" : "保存并启用"}
            </button>
            <button disabled={isTesting || !ready} onClick={onTest} type="button">
              {isTesting ? "测试中" : "测试连接"}
            </button>
          </div>
        </>
      )}
      <div className="llm-provider-list" aria-label="已保存 LLM Provider">
        {providers.length === 0 ? (
          <div className="llm-provider-empty">暂无已保存 Provider</div>
        ) : (
          providers.map((provider) => (
            <div className={`llm-provider-row ${provider.active ? "active" : ""}`} key={provider.id}>
              <div>
                <strong>{provider.providerName}</strong>
                <span>{provider.model}</span>
                <small>{provider.baseUrl}</small>
                {provider.lastTestMessage && (
                  <small className="llm-test-detail" title={provider.lastTestMessage}>
                    {provider.lastTestMessage}
                  </small>
                )}
              </div>
              <div className="llm-provider-side">
                <small>{provider.apiKeyMasked}</small>
                <button
                  disabled={provider.active || activatingProviderId === provider.id}
                  onClick={() => onActivateProvider(provider.id)}
                  type="button"
                >
                  {provider.active ? "当前" : activatingProviderId === provider.id ? "启用中" : "启用"}
                </button>
              </div>
              {provider.lastTestStatus && (
                <em className={provider.lastTestStatus === "OK" ? "ok" : ""}>
                  {provider.lastTestStatus}
                </em>
              )}
            </div>
          ))
        )}
      </div>
      {status && <p className={`llm-status ${status.startsWith("连接成功") ? "success" : ""}`}>{status}</p>}
    </section>
  );
}

function EmbeddingConfigPanel({
  activatingProviderId,
  form,
  isSaving,
  isTesting,
  onActivateProvider,
  onChange,
  onClose,
  onModeChange,
  onSave,
  onTest,
  providers,
  runtimeStatus,
  status,
}: {
  activatingProviderId: number | null;
  form: EmbeddingFormState;
  isSaving: boolean;
  isTesting: boolean;
  onActivateProvider: (providerId: number) => void;
  onChange: (next: EmbeddingFormState) => void;
  onClose: () => void;
  onModeChange: (mode: EmbeddingMode) => void;
  onSave: () => void;
  onTest: () => void;
  providers: EmbeddingProvider[];
  runtimeStatus: RuntimeStatus | null;
  status: string;
}) {
  const activeProvider = providers.find((provider) => provider.active);
  const ready = Boolean(runtimeStatus?.llm.embeddingProvider && !runtimeStatus.llm.embeddingProvider.includes("local"));
  const canTest = Boolean(activeProvider);
  const stateLabel = ready
    ? runtimeStatus?.llm.embeddingProvider ?? "已接通"
    : activeProvider
      ? "待测试"
      : "未配置";

  return (
    <section className="panel llm-panel">
      <div className="panel-title">
        <Fingerprint size={19} />
        <h2>Embedding</h2>
        <span className={`panel-count ${ready ? "ready" : ""}`}>{stateLabel}</span>
        <button className="llm-close" onClick={onClose} type="button">
          关闭
        </button>
      </div>
      <div className="llm-switch-row embedding-mode-row">
        {(["qwen", "openai", "bge"] as EmbeddingMode[]).map((mode) => (
          <button
            className={form.mode === mode ? "active" : ""}
            key={mode}
            onClick={() => onModeChange(mode)}
            type="button"
          >
            {mode.toUpperCase()}
          </button>
        ))}
      </div>
      <div className="llm-fields">
        <label>
          <span>名称</span>
          <input
            onChange={(event) => onChange({ ...form, providerName: event.currentTarget.value })}
            placeholder={embeddingDefaults[form.mode].providerName}
            value={form.providerName}
          />
        </label>
        <label>
          <span>Base URL</span>
          <input
            onChange={(event) => onChange({ ...form, baseUrl: event.currentTarget.value })}
            placeholder={embeddingDefaults[form.mode].baseUrl}
            value={form.baseUrl}
          />
        </label>
        <label>
          <span>模型</span>
          <input
            onChange={(event) => onChange({ ...form, model: event.currentTarget.value })}
            placeholder={embeddingDefaults[form.mode].model}
            value={form.model}
          />
        </label>
        <label>
          <span>API Key</span>
          <input
            autoComplete="off"
            onChange={(event) => onChange({ ...form, apiKey: event.currentTarget.value })}
            placeholder={form.mode === "bge" ? "本地 BGE 可留空" : "sk-..."}
            type="password"
            value={form.apiKey}
          />
        </label>
        <label>
          <span>超时</span>
          <input
            min={5}
            max={120}
            onChange={(event) => onChange({ ...form, timeoutSeconds: Number(event.currentTarget.value) })}
            type="number"
            value={form.timeoutSeconds}
          />
        </label>
      </div>
      <div className="llm-actions">
        <button disabled={isSaving} onClick={onSave} type="button">
          {isSaving ? "保存中" : "保存并启用"}
        </button>
        <button disabled={isTesting || !canTest} onClick={onTest} type="button">
          {isTesting ? "测试中" : "测试连接"}
        </button>
      </div>
      <div className="runtime-grid embedding-runtime-grid">
        <RuntimeCell
          label="Runtime"
          tone={ready ? "done" : "warn"}
          value={runtimeStatus?.llm.embeddingProvider ?? "unknown"}
          detail={runtimeStatus?.llm.embeddingDiagnostic ?? "waiting"}
        />
        <RuntimeCell
          label="Vector"
          tone={runtimeStatus?.llm.vectorStoreConnected ? "done" : "warn"}
          value={runtimeStatus?.llm.embeddingIndex ?? "-"}
          detail={`${runtimeStatus?.llm.vectorStoreMode ?? "memory"} / ${runtimeStatus?.llm.embeddingDimensions ?? 0} dims`}
        />
      </div>
      <div className="llm-provider-list" aria-label="已保存 Embedding Provider">
        {providers.length === 0 ? (
          <div className="llm-provider-empty">暂无已保存 Embedding Provider</div>
        ) : (
          providers.map((provider) => (
            <div className={`llm-provider-row ${provider.active ? "active" : ""}`} key={provider.id}>
              <div>
                <strong>{provider.providerName}</strong>
                <span>{provider.mode.toUpperCase()} / {provider.model}</span>
                <small>{provider.baseUrl}</small>
                {provider.lastTestMessage && (
                  <small className="llm-test-detail" title={provider.lastTestMessage}>
                    {provider.lastTestMessage}
                  </small>
                )}
              </div>
              <div className="llm-provider-side">
                <small>{provider.apiKeyMasked || "no key"}</small>
                <button
                  disabled={provider.active || activatingProviderId === provider.id}
                  onClick={() => onActivateProvider(provider.id)}
                  type="button"
                >
                  {provider.active ? "当前" : activatingProviderId === provider.id ? "启用中" : "启用"}
                </button>
              </div>
              {provider.lastTestStatus && (
                <em className={provider.lastTestStatus === "OK" ? "ok" : ""}>
                  {provider.lastTestStatus}
                </em>
              )}
            </div>
          ))
        )}
      </div>
      {status && <p className={`llm-status ${status.startsWith("连接成功") ? "success" : ""}`}>{status}</p>}
    </section>
  );
}

function RuntimeStatusPanel({
  isRebuildingVectorIndex,
  onRebuildVectorIndex,
  rebuildStatus,
  status,
}: {
  isRebuildingVectorIndex: boolean;
  onRebuildVectorIndex: () => void;
  rebuildStatus: string;
  status: RuntimeStatus | null;
}) {
  const chatReady = Boolean(status?.llm.chatEnabled && status.llm.chatConfigured);
  const embeddingFallback = status?.llm.embeddingProvider.includes("fallback")
    || status?.llm.embeddingProvider.includes("local");
  const vectorStoreReady = Boolean(status?.llm.vectorStoreConnected);

  return (
    <section className="panel runtime-panel">
      <div className="panel-title">
        <BrainCircuit size={19} />
        <h2>Runtime</h2>
        <span className={`panel-count ${status ? "ready" : ""}`}>{status?.profile ?? "offline"}</span>
      </div>
      <div className="runtime-grid">
        <RuntimeCell
          label="MySQL"
          tone={status?.database.connected ? "done" : "warn"}
          value={status?.database.connected ? status.database.product : "not connected"}
          detail={status?.database.hook ?? "waiting"}
        />
        <RuntimeCell
          label="Redis"
          tone={status?.redis.connected ? "done" : "warn"}
          value={status?.redis.connected ? "connected" : "not connected"}
          detail={`cache=${status?.redis.answerCacheMode ?? "-"} / memory=${status?.redis.shortTermMemoryMode ?? "-"}`}
        />
        <RuntimeCell
          label="DeepSeek Chat"
          tone={chatReady ? "done" : "warn"}
          value={status?.llm.model ?? "unknown"}
          detail={chatReady ? "chat completions enabled" : "save provider first"}
        />
        <RuntimeCell
          label="Embedding"
          tone={embeddingFallback ? "warn" : "done"}
          value={status?.llm.embeddingProvider ?? "unknown"}
          detail={`${status?.llm.embeddingDiagnostic ?? status?.llm.embeddingIndex ?? "-"} / ${status?.llm.embeddingDimensions ?? 0} dims`}
        />
        <RuntimeCell
          label="Vector Store"
          tone={vectorStoreReady ? "done" : "warn"}
          value={status?.llm.vectorStoreMode ?? "memory"}
          detail={vectorStoreReady ? status?.llm.vectorStoreEndpoint ?? "" : "memory fallback"}
        />
        <RuntimeCell
          label="Rerank"
          tone={status?.llm.rerankProvider === "rerank-disabled" ? "warn" : "done"}
          value={status?.llm.rerankProvider ?? "unknown"}
          detail={status?.llm.rerankDiagnostic ?? "waiting"}
        />
      </div>
      <button
        className="text-button vector-rebuild-button"
        disabled={isRebuildingVectorIndex}
        onClick={onRebuildVectorIndex}
        type="button"
      >
        <GitBranch size={16} />
        {isRebuildingVectorIndex ? "重建中" : "重建向量索引"}
      </button>
      {rebuildStatus && (
        <p className={`vector-rebuild-status ${rebuildStatus.startsWith("重建完成") ? "success" : ""}`}>
          {rebuildStatus}
        </p>
      )}
    </section>
  );
}

function RuntimeCell({
  detail,
  label,
  tone,
  value,
}: {
  detail: string;
  label: string;
  tone: "done" | "warn";
  value: string;
}) {
  return (
    <div className={`runtime-cell ${tone}`}>
      <span>{label}</span>
      <strong>{value}</strong>
      <small>{detail}</small>
    </div>
  );
}

function VectorStoreInspectorPanel({
  inspect,
  onRefresh,
}: {
  inspect: VectorIndexStatusResponse | null;
  onRefresh: () => void;
}) {
  const ready = Boolean(inspect?.connected && inspect.collectionExists);
  return (
    <section className="panel compact-panel">
      <div className="panel-title">
        <Database size={19} />
        <h2>Vector Store Inspector</h2>
        <button className="panel-action" onClick={onRefresh} type="button">
          refresh
        </button>
      </div>
      {!inspect ? (
        <div className="library-empty">Waiting for vector store status.</div>
      ) : (
        <div className="threadpool-grid">
          <RuntimeCell
            label="Store"
            tone={ready ? "done" : "warn"}
            value={inspect.vectorStoreMode}
            detail={inspect.connected ? "connected" : inspect.message}
          />
          <RuntimeCell
            label="Collection"
            tone={inspect.collectionStatus === "green" ? "done" : "warn"}
            value={inspect.collectionStatus}
            detail={inspect.collectionName || "memory fallback"}
          />
          <RuntimeCell
            label="Points"
            tone={inspect.pointsCount > 0 ? "done" : "warn"}
            value={`${inspect.pointsCount}`}
            detail={`indexed=${inspect.indexedVectorsCount}, segments=${inspect.segmentsCount}`}
          />
          <RuntimeCell
            label="Vector"
            tone={inspect.dimensions > 0 ? "done" : "warn"}
            value={`${inspect.dimensions} dims`}
            detail={inspect.distance || "distance unknown"}
          />
          <RuntimeCell
            label="Endpoint"
            tone={inspect.connected ? "done" : "warn"}
            value={inspect.endpoint || "-"}
            detail={inspect.message || "qdrant collection inspected"}
          />
        </div>
      )}
    </section>
  );
}

function ThreadPoolInspectorPanel({
  inspect,
  onRefresh,
}: {
  inspect: ThreadPoolInspectResponse | null;
  onRefresh: () => void;
}) {
  const heapUsedMb = inspect ? bytesToMb(inspect.heapUsedBytes) : "0";
  const heapMaxMb = inspect ? bytesToMb(inspect.heapMaxBytes) : "0";
  return (
    <section className="panel compact-panel">
      <div className="panel-title">
        <GitBranch size={19} />
        <h2>JVM ThreadPool Inspector</h2>
        <button className="panel-action" onClick={onRefresh} type="button">
          refresh
        </button>
      </div>
      {!inspect ? (
        <div className="library-empty">Waiting for JVM thread pool metrics.</div>
      ) : (
        <div className="threadpool-grid">
          <RuntimeCell
            label="Pool"
            tone="done"
            value={`${inspect.poolSize}/${inspect.corePoolSize}-${inspect.maxPoolSize}`}
            detail={inspect.threadNamePrefix}
          />
          <RuntimeCell
            label="Active"
            tone={inspect.activeCount > 0 ? "warn" : "done"}
            value={`${inspect.activeCount}`}
            detail={`queue=${inspect.queueSize}, remaining=${inspect.queueRemainingCapacity}`}
          />
          <RuntimeCell
            label="Tasks"
            tone="done"
            value={`${inspect.completedTaskCount}/${inspect.taskCount}`}
            detail="completed / submitted"
          />
          <RuntimeCell
            label="Reject"
            tone="warn"
            value={inspect.rejectionPolicy}
            detail="bounded queue overflow policy"
          />
          <RuntimeCell
            label="Heap"
            tone="done"
            value={`${heapUsedMb}/${heapMaxMb} MB`}
            detail={`nonHeap=${bytesToMb(inspect.nonHeapUsedBytes)} MB`}
          />
          <RuntimeCell
            label="CPU"
            tone="done"
            value={`${inspect.availableProcessors}`}
            detail={inspect.executorName}
          />
        </div>
      )}
    </section>
  );
}

function SseProgressInspectorPanel({ inspect }: { inspect: SseInspectState }) {
  const snapshot = inspect.lastSnapshot;
  return (
    <section className="panel compact-panel">
      <div className="panel-title">
        <Clock3 size={19} />
        <h2>SSE Progress Inspector</h2>
        <span className={`panel-count ${inspect.status === "open" ? "ready" : ""}`}>{inspect.status}</span>
      </div>
      <div className="threadpool-grid">
        <RuntimeCell
          label="Stream"
          tone={inspect.status === "open" ? "done" : "warn"}
          value={inspect.status}
          detail={inspect.url || "waiting for RUNNING job"}
        />
        <RuntimeCell
          label="Last Event"
          tone={snapshot ? "done" : "warn"}
          value={inspect.lastEventAt || "-"}
          detail={snapshot ? `job#${snapshot.jobId} / video#${snapshot.videoId}` : "no event yet"}
        />
        <RuntimeCell
          label="Progress"
          tone={snapshot?.status === "RUNNING" ? "warn" : "done"}
          value={snapshot ? `${snapshot.progress}%` : "-"}
          detail={snapshot?.currentStep ?? "idle"}
        />
        <RuntimeCell
          label="Disconnect"
          tone={inspect.disconnectCount > 0 ? "warn" : "done"}
          value={`${inspect.disconnectCount}`}
          detail="EventSource error count"
        />
      </div>
    </section>
  );
}

function AsrDiagnosticPanel({
  diagnostic,
  isAligningOcr,
  isEvaluatingOcr,
  isFusingOcr,
  isRefiningLowConfidence,
  isRepairingText,
  ocrQuality,
  ocrStatus,
  onAlignOcr,
  onEvaluateOcr,
  onFuseOcr,
  onRefineLowConfidence,
  onRepairText,
  onRefresh,
  onReprocess,
  textRepairStatus,
  video,
}: {
  diagnostic: AsrDiagnostic | null;
  isAligningOcr: boolean;
  isEvaluatingOcr: boolean;
  isFusingOcr: boolean;
  isRefiningLowConfidence: boolean;
  isRepairingText: boolean;
  ocrQuality: OcrSubtitleQuality | null;
  ocrStatus: string;
  onAlignOcr: () => void;
  onEvaluateOcr: () => void;
  onFuseOcr: () => void;
  onRefineLowConfidence: () => void;
  onRepairText: () => void;
  onRefresh: () => void;
  onReprocess: () => void;
  textRepairStatus: string;
  video: VideoAsset | null;
}) {
  const ready = Boolean(diagnostic?.asrJsonExists && diagnostic.transcriptCount > 0);
  const running = diagnostic?.lastJobStatus === "RUNNING";
  const quality = diagnostic?.quality;
  const qualityReady = Boolean(quality && !quality.garbledRisk && quality.traditionalCount === 0);
  return (
    <section className="panel compact-panel">
      <div className="panel-title">
        <FileText size={19} />
        <h2>ASR Diagnostic</h2>
        <span className={`panel-count ${ready ? "ready" : ""}`}>
          {diagnostic ? diagnostic.lastJobStatus : "waiting"}
        </span>
        <button className="panel-action" onClick={onRefresh} type="button">
          refresh
        </button>
      </div>
      {!diagnostic ? (
        <div className="library-empty">Select a video to inspect audio.wav, asr.json and whisper logs.</div>
      ) : (
        <div className="threadpool-grid">
          {quality && (
            <>
              <RuntimeCell
                label="Text Quality"
                tone={qualityReady ? "done" : "warn"}
                value={qualityReady ? "clean" : "risk"}
                detail={`garbled=${quality.garbledRisk}, traditional=${quality.traditionalCount}, cjk=${quality.cjkCount}`}
              />
              <RuntimeCell
                label="Sample"
                tone={qualityReady ? "done" : "warn"}
                value={quality.replacementCount === 0 ? "no mojibake" : `${quality.replacementCount} replacements`}
                detail={quality.sample || "waiting for ASR sample"}
              />
            </>
          )}
          <RuntimeCell
            label="Model"
            tone={diagnostic.modelExists ? "done" : "warn"}
            value={diagnostic.modelExists ? "ready" : "missing"}
            detail={diagnostic.modelPath}
          />
          <RuntimeCell
            label="Decode Params"
            tone="done"
            value={`beam ${diagnostic.beamSize}`}
            detail={`lang=${diagnostic.language || "auto"}, bestOf=${diagnostic.bestOf}, maxLen=${diagnostic.maxLen}`}
          />
          <RuntimeCell
            label="Hotword Prompt"
            tone={diagnostic.promptPreview ? "done" : "warn"}
            value={diagnostic.promptPreview ? "enabled" : "empty"}
            detail={diagnostic.promptPreview || "no prompt configured"}
          />
          <RuntimeCell
            label="Audio"
            tone={diagnostic.audioExists ? "done" : "warn"}
            value={diagnostic.audioExists ? `${bytesToMb(diagnostic.audioSizeBytes)} MB` : "missing"}
            detail="audio.wav from ffmpeg"
          />
          <RuntimeCell
            label="Audio Filter"
            tone={diagnostic.audioFilter ? "done" : "warn"}
            value={diagnostic.audioFilter ? "enhanced" : "basic"}
            detail={diagnostic.audioFilter || "no ffmpeg audio filter"}
          />
          <RuntimeCell
            label="ASR JSON"
            tone={diagnostic.asrJsonExists ? "done" : "warn"}
            value={diagnostic.asrJsonExists ? `${bytesToMb(diagnostic.asrJsonSizeBytes)} MB` : "missing"}
            detail={`${diagnostic.transcriptCount} transcript rows`}
          />
          <RuntimeCell
            label="Job"
            tone={diagnostic.lastJobStatus === "DONE" ? "done" : "warn"}
            value={diagnostic.lastJobStep}
            detail={diagnostic.lastJobError || diagnostic.videoStatus}
          />
          <RuntimeCell
            label="ffmpeg.log"
            tone={diagnostic.ffmpegLogTail ? "done" : "warn"}
            value={diagnostic.ffmpegLogTail ? "captured" : "empty"}
            detail={diagnostic.ffmpegLogTail || "no ffmpeg log tail"}
          />
          <RuntimeCell
            label="asr.log"
            tone={diagnostic.asrLogTail ? "done" : "warn"}
            value={diagnostic.asrLogTail ? "captured" : "empty"}
            detail={diagnostic.asrLogTail || diagnostic.asrPath}
          />
        </div>
      )}
      <AdvancedOcrQualityPanel
        disabled={!video || !ready || running || isEvaluatingOcr || isFusingOcr || isAligningOcr || isRefiningLowConfidence}
        isAligning={isAligningOcr}
        isEvaluating={isEvaluatingOcr}
        isFusing={isFusingOcr}
        isRefining={isRefiningLowConfidence}
        onAlign={onAlignOcr}
        onEvaluate={onEvaluateOcr}
        onFuse={onFuseOcr}
        onRefine={onRefineLowConfidence}
        quality={ocrQuality}
        status={ocrStatus}
      />
      <TranscriptTextRepairPanel
        disabled={!video || !ready || running || isRepairingText || isEvaluatingOcr || isFusingOcr || isAligningOcr || isRefiningLowConfidence}
        isRepairing={isRepairingText}
        onRepair={onRepairText}
        status={textRepairStatus}
      />
      <AsrReprocessPanel
        disabled={!video || running || isEvaluatingOcr || isFusingOcr || isAligningOcr || isRefiningLowConfidence || isRepairingText}
        onReprocess={onReprocess}
        running={running}
      />
    </section>
  );
}

function AsrReprocessPanel({
  disabled,
  onReprocess,
  running,
}: {
  disabled: boolean;
  onReprocess: () => void;
  running: boolean;
}) {
  return (
    <div className="ocr-quality-card">
      <div className="ocr-quality-head">
        <div>
          <strong>ASR Reprocess</strong>
          <small>用当前热词提示重新跑 ffmpeg + Whisper，并替换旧字幕</small>
        </div>
        <span className={running ? "" : "ready"}>{running ? "running" : "ready"}</span>
      </div>
      <div className="ocr-sample-preview">
        <span>Source-level accuracy path</span>
        <strong>适合老视频字幕英文术语偏差大时使用</strong>
        <small>任务完成后会自动重建总结与向量索引，旧字幕会被本次识别结果替换。</small>
      </div>
      <div className="ocr-actions single-action">
        <button disabled={disabled} onClick={onReprocess} type="button">
          {running ? "识别中" : "重新识别字幕"}
        </button>
      </div>
    </div>
  );
}

function TranscriptTextRepairPanel({
  disabled,
  isRepairing,
  onRepair,
  status,
}: {
  disabled: boolean;
  isRepairing: boolean;
  onRepair: () => void;
  status: string;
}) {
  return (
    <div className="ocr-quality-card">
      <div className="ocr-quality-head">
        <div>
          <strong>Context Term Repair</strong>
          <small>视频级上下文技术词修复 / 重建总结与向量索引</small>
        </div>
        <span className={status.includes("repaired=") ? "ready" : ""}>
          {isRepairing ? "running" : "guarded"}
        </span>
      </div>
      <div className="ocr-sample-preview">
        <span>Conservative ASR text layer</span>
        <strong>Redis / MySQL / RAG / Agent / Embedding / Docker 等技术词按全片上下文修正</strong>
        <small>仅在上下文证据足够时替换，避免把字幕改成无来源内容。</small>
      </div>
      <div className="ocr-actions single-action">
        <button disabled={disabled} onClick={onRepair} type="button">
          {isRepairing ? "修复中" : "高级文本修复"}
        </button>
      </div>
      {status && <p className={`ocr-status ${status.includes("repaired=") ? "success" : ""}`}>{status}</p>}
    </div>
  );
}

function AdvancedOcrQualityPanel({
  disabled,
  isAligning,
  isEvaluating,
  isFusing,
  isRefining,
  onAlign,
  onEvaluate,
  onFuse,
  onRefine,
  quality,
  status,
}: {
  disabled: boolean;
  isAligning: boolean;
  isEvaluating: boolean;
  isFusing: boolean;
  isRefining: boolean;
  onAlign: () => void;
  onEvaluate: () => void;
  onFuse: () => void;
  onRefine: () => void;
  quality: OcrSubtitleQuality | null;
  status: string;
}) {
  const hasQuality = Boolean(quality);
  const improved = quality ? quality.averageFusedSimilarity > quality.averageSimilarity : false;
  const replacementCount = quality?.appliedReplacementCount || quality?.replacementCount || 0;
  const samplePreview = quality?.samples.find((sample) => sample.replacementSuggested) ?? quality?.samples[0];

  return (
    <div className="ocr-quality-card">
      <div className="ocr-quality-head">
        <div>
          <strong>ASR + OCR Dual Channel</strong>
          <small>visual subtitle evidence, strong alignment and low-confidence refinement</small>
        </div>
        <span className={quality?.ocrAvailable ? "ready" : ""}>
          {quality ? `${quality.ocrHitCount}/${quality.sampledCount}` : "not tested"}
        </span>
      </div>
      {hasQuality && quality ? (
        <div className="ocr-quality-grid">
          <RuntimeCell
            label="ASR Similarity"
            tone={quality.averageSimilarity >= 0.75 ? "done" : "warn"}
            value={formatPercent(quality.averageSimilarity)}
            detail={`CER=${formatPercent(quality.averageCer)}`}
          />
          <RuntimeCell
            label="Fused Similarity"
            tone={improved ? "done" : "warn"}
            value={formatPercent(quality.averageFusedSimilarity)}
            detail={`suggested=${quality.replacementCount}, applied=${quality.appliedReplacementCount}`}
          />
          <RuntimeCell
            label="OCR"
            tone={quality.ocrAvailable ? "done" : "warn"}
            value={quality.ocrAvailable ? "available" : "unavailable"}
            detail={quality.message}
          />
        </div>
      ) : (
        <div className="library-empty">Evaluate OCR first, then write visual evidence back when it is reliable.</div>
      )}
      {samplePreview && (
        <div className="ocr-sample-preview">
          <span>{formatTime(samplePreview.startMs)} / confidence {formatPercent(samplePreview.confidence)}</span>
          <strong>{samplePreview.fusedText || samplePreview.asrText}</strong>
          <small>OCR: {samplePreview.ocrText || "no visual subtitle"}</small>
        </div>
      )}
      <div className="ocr-actions ocr-actions-four">
        <button disabled={disabled} onClick={onEvaluate} type="button">
          {isEvaluating ? "Evaluating" : "Evaluate"}
        </button>
        <button disabled={disabled || !quality?.ocrAvailable} onClick={onFuse} type="button">
          {isFusing ? "Fusing" : `Safe fuse${replacementCount ? ` ${replacementCount}` : ""}`}
        </button>
        <button disabled={disabled || !quality?.ocrAvailable} onClick={onAlign} type="button">
          {isAligning ? "Aligning" : "Strong align"}
        </button>
        <button disabled={disabled || !quality?.ocrAvailable} onClick={onRefine} type="button">
          {isRefining ? "Refining" : "Low confidence"}
        </button>
      </div>
      {status && <p className={`ocr-status ${status.includes("applied=") || status.includes("OCR ") ? "success" : ""}`}>{status}</p>}
    </div>
  );
}

function OcrQualityPanel({
  disabled,
  isEvaluating,
  isFusing,
  onEvaluate,
  onFuse,
  quality,
  status,
}: {
  disabled: boolean;
  isEvaluating: boolean;
  isFusing: boolean;
  onEvaluate: () => void;
  onFuse: () => void;
  quality: OcrSubtitleQuality | null;
  status: string;
}) {
  const hasQuality = Boolean(quality);
  const improved = quality ? quality.averageFusedSimilarity > quality.averageSimilarity : false;
  const replacementCount = quality?.appliedReplacementCount || quality?.replacementCount || 0;
  const samplePreview = quality?.samples.find((sample) => sample.replacementSuggested) ?? quality?.samples[0];

  return (
    <div className="ocr-quality-card">
      <div className="ocr-quality-head">
        <div>
          <strong>Burned-in Subtitle OCR</strong>
          <small>画面字幕准确率评估 / 保守融合修复</small>
        </div>
        <span className={quality?.ocrAvailable ? "ready" : ""}>
          {quality ? `${quality.ocrHitCount}/${quality.sampledCount}` : "not tested"}
        </span>
      </div>
      {hasQuality && quality ? (
        <div className="ocr-quality-grid">
          <RuntimeCell
            label="ASR Similarity"
            tone={quality.averageSimilarity >= 0.75 ? "done" : "warn"}
            value={formatPercent(quality.averageSimilarity)}
            detail={`CER=${formatPercent(quality.averageCer)}`}
          />
          <RuntimeCell
            label="Fused Similarity"
            tone={improved ? "done" : "warn"}
            value={formatPercent(quality.averageFusedSimilarity)}
            detail={`suggested=${quality.replacementCount}, applied=${quality.appliedReplacementCount}`}
          />
          <RuntimeCell
            label="OCR"
            tone={quality.ocrAvailable ? "done" : "warn"}
            value={quality.ocrAvailable ? "available" : "unavailable"}
            detail={quality.message}
          />
        </div>
      ) : (
        <div className="library-empty">先评估 OCR 命中率，再决定是否写回融合结果。</div>
      )}
      {samplePreview && (
        <div className="ocr-sample-preview">
          <span>{formatTime(samplePreview.startMs)} · confidence {formatPercent(samplePreview.confidence)}</span>
          <strong>{samplePreview.fusedText || samplePreview.asrText}</strong>
          <small>OCR: {samplePreview.ocrText || "no visual subtitle"}</small>
        </div>
      )}
      <div className="ocr-actions">
        <button disabled={disabled} onClick={onEvaluate} type="button">
          {isEvaluating ? "评估中" : "统计准确率"}
        </button>
        <button disabled={disabled || !quality?.ocrAvailable} onClick={onFuse} type="button">
          {isFusing ? "融合中" : `一键融合修复${replacementCount ? ` ${replacementCount}` : ""}`}
        </button>
      </div>
      {status && <p className={`ocr-status ${status.includes("已写回") || status.includes("OCR 命中") ? "success" : ""}`}>{status}</p>}
    </div>
  );
}

function TermGlossaryPanel({
  entries,
  form,
  isSaving,
  onAdd,
  onChange,
  onDelete,
  onRefresh,
  onToggle,
  pendingEntryId,
  status,
}: {
  entries: TermGlossaryEntry[];
  form: TermGlossaryFormState;
  isSaving: boolean;
  onAdd: () => void;
  onChange: (form: TermGlossaryFormState) => void;
  onDelete: (entryId: number) => void;
  onRefresh: () => void;
  onToggle: (entry: TermGlossaryEntry) => void;
  pendingEntryId: number | null;
  status: string;
}) {
  const canAdd = form.sourcePattern.trim().length > 0 && form.replacement.trim().length > 0 && !isSaving;
  return (
    <section className="panel compact-panel">
      <div className="panel-title">
        <KeyRound size={19} />
        <h2>ASR Term Glossary</h2>
        <span className={`panel-count ${entries.some((entry) => entry.enabled) ? "ready" : ""}`}>
          {entries.filter((entry) => entry.enabled).length}/{entries.length}
        </span>
        <button className="panel-action" onClick={onRefresh} type="button">
          refresh
        </button>
      </div>
      <div className="term-glossary-form">
        <input
          aria-label="Misrecognized term"
          onChange={(event) => onChange({ ...form, sourcePattern: event.target.value })}
          placeholder="misheard term or regex"
          type="text"
          value={form.sourcePattern}
        />
        <input
          aria-label="Canonical term"
          onChange={(event) => onChange({ ...form, replacement: event.target.value })}
          placeholder="canonical term"
          type="text"
          value={form.replacement}
        />
        <button disabled={!canAdd} onClick={onAdd} type="button">
          {isSaving ? "saving" : "add term"}
        </button>
      </div>
      {entries.length === 0 ? (
        <div className="library-empty">Add terms like my sql -&gt; MySQL or cloud code -&gt; Claude Code.</div>
      ) : (
        <div className="term-glossary-list">
          {entries.map((entry) => (
            <div className={`term-glossary-row ${entry.enabled ? "enabled" : ""}`} key={entry.id}>
              <div>
                <span>{entry.sourcePattern}</span>
                <strong>{entry.replacement}</strong>
              </div>
              <div className="term-glossary-actions">
                <button disabled={pendingEntryId === entry.id} onClick={() => onToggle(entry)} type="button">
                  {entry.enabled ? "disable" : "enable"}
                </button>
                <button disabled={pendingEntryId === entry.id} onClick={() => onDelete(entry.id)} type="button">
                  delete
                </button>
              </div>
            </div>
          ))}
        </div>
      )}
      {status && <p className={`ocr-status ${status.includes("saved") || status.includes("enabled") ? "success" : ""}`}>{status}</p>}
    </section>
  );
}

function bytesToMb(bytes: number) {
  if (!Number.isFinite(bytes) || bytes <= 0) return "0";
  return Math.round(bytes / 1024 / 1024).toString();
}

function MysqlIndexInspectorPanel({
  onRefresh,
  plans,
}: {
  onRefresh: () => void;
  plans: MysqlExplainPlan[];
}) {
  return (
    <section className="panel compact-panel">
      <div className="panel-title">
        <Database size={19} />
        <h2>MySQL Index Inspector</h2>
        <button className="panel-action" onClick={onRefresh} type="button">
          EXPLAIN
        </button>
      </div>
      {plans.length === 0 ? (
        <div className="library-empty">Waiting for MySQL EXPLAIN plans.</div>
      ) : (
        <div className="index-plan-list">
          {plans.map((plan) => (
            <div className="index-plan-card" key={plan.scenario}>
              <div className="index-plan-head">
                <span>{plan.scenario}</span>
                <strong>{plan.keyName || "no key"}</strong>
              </div>
              <div className="index-plan-grid">
                <span>
                  <strong>{plan.accessType || "-"}</strong>
                  <small>type</small>
                </span>
                <span>
                  <strong>{plan.rows}</strong>
                  <small>rows</small>
                </span>
              </div>
              <small>{plan.hook}</small>
              <small>possible: {plan.possibleKeys || "-"}</small>
              <small>extra: {plan.extra || "-"}</small>
            </div>
          ))}
        </div>
      )}
    </section>
  );
}

function RecoveryPanel({
  failedJobs,
  isLoading,
  onRefresh,
  onRetry,
  onSelect,
}: {
  failedJobs: FailedJob[];
  isLoading: boolean;
  onRefresh: () => void;
  onRetry: (videoId: number) => void;
  onSelect: (videoId: number) => void;
}) {
  return (
    <section className="panel compact-panel">
      <div className="panel-title">
        <ShieldCheck size={19} />
        <h2>Recovery Queue</h2>
        <button className="panel-action" onClick={onRefresh} type="button">
          refresh
        </button>
      </div>
      <div className="library-empty">Only latest FAILED jobs can enter compensation retry; DONE jobs rely on MD5 reuse.</div>
      {failedJobs.length === 0 ? (
        <div className="library-empty">No failed DAG jobs waiting for compensation.</div>
      ) : (
        <div className="recovery-list">
          {failedJobs.map((job) => (
            <div className="recovery-card" key={job.jobId}>
              <button className="recovery-card-main" onClick={() => onSelect(job.videoId)} type="button">
                <strong>{job.originalName}</strong>
                <small>job#{job.jobId} / video#{job.videoId} / retry {job.retryCount}</small>
              </button>
              <div className="recovery-meta">
                <span>{job.currentStep}</span>
                <small>{job.errorMessage || "No error message captured"}</small>
              </div>
              <button
                className="text-button retry-job-button"
                disabled={isLoading}
                onClick={() => onRetry(job.videoId)}
                type="button"
              >
                <GitBranch size={15} />
                retry DAG
              </button>
            </div>
          ))}
        </div>
      )}
    </section>
  );
}

function RedisKeyInspectorPanel({
  inspect,
  onRefresh,
}: {
  inspect: RedisInspectResponse | null;
  onRefresh: () => void;
}) {
  const keys = inspect?.keys ?? [];
  return (
    <section className="panel compact-panel">
      <div className="panel-title">
        <Zap size={19} />
        <h2>Redis Key Inspector</h2>
        <span className={`panel-count ${inspect?.connected ? "ready" : ""}`}>
          {inspect?.connected ? "online" : "offline"}
        </span>
        <button className="panel-action" onClick={onRefresh} type="button">
          scan
        </button>
      </div>
      {keys.length === 0 ? (
        <div className="library-empty">Waiting for Redis key inspection.</div>
      ) : (
        <div className="redis-inspect-list">
          {keys.map((item) => (
            <div className={`redis-inspect-card ${item.exists ? "hit" : ""}`} key={item.pattern}>
              <div className="redis-inspect-head">
                <span>{item.hook}</span>
                <strong>{item.exists ? item.sampleKey : item.pattern}</strong>
              </div>
              <div className="redis-inspect-grid">
                <span>
                  <strong>{item.exists ? item.type || "-" : "miss"}</strong>
                  <small>type</small>
                </span>
                <span>
                  <strong>{ttlLabel(item.ttlSeconds)}</strong>
                  <small>ttl</small>
                </span>
              </div>
              <small>{item.note}</small>
            </div>
          ))}
        </div>
      )}
    </section>
  );
}

function ttlLabel(ttlSeconds: number) {
  if (ttlSeconds === -2) return "none";
  if (ttlSeconds === -1) return "persist";
  return `${ttlSeconds}s`;
}

function RedisHooksPanel({
  context,
  latestMessage,
  status,
}: {
  context: AgentContext | null;
  latestMessage: ChatMessage | undefined;
  status: RuntimeStatus | null;
}) {
  const memoryDetail = traceDetail(latestMessage?.trace, "MemoryTool") || "ask follow-up to verify memory";
  const retrieveDetail = traceDetail(latestMessage?.trace, "VectorRetrieveTool") || "ask once to verify retrieval";
  const llmDetail = traceDetail(latestMessage?.trace, "LlmGenerateTool") || "ask once to verify generation";
  const hooks = [
    {
      label: "SETNX Lock",
      key: `mode=${status?.redis.dedupeLockMode ?? "-"}`,
      value: "video:lock:{md5}",
    },
    {
      label: "Progress Cache",
      key: `mode=${status?.redis.progressCacheMode ?? "-"}`,
      value: "omnivid:progress:{videoId}",
    },
    {
      label: "Rate Limit",
      key: `mode=${status?.redis.rateLimitMode ?? "-"}`,
      value: "omnivid:agent:rate:{scope}",
    },
    {
      label: "Semantic Cache",
      key: latestMessage?.cacheHit ? "cache hit" : `mode=${status?.redis.answerCacheMode ?? "-"}`,
      value: llmDetail,
    },
    {
      label: "Short Memory",
      key: `${context?.messageCount ?? 0}/${context?.windowLimit ?? 6}`,
      value: memoryDetail,
    },
    {
      label: "Vector Recall",
      key: status?.llm.vectorStoreConnected ? status.llm.vectorStoreMode : status?.llm.embeddingProvider ?? "unknown",
      value: retrieveDetail,
    },
  ];

  return (
    <section className="panel compact-panel">
      <div className="panel-title">
        <Zap size={19} />
        <h2>Redis Hooks</h2>
        <span className={`panel-count ${status?.redis.connected ? "ready" : ""}`}>
          {status?.redis.connected ? "online" : "local"}
        </span>
      </div>
      <div className="hook-list">
        {hooks.map((hook) => (
          <div className="hook-card" key={hook.label}>
            <div className="hook-card-head">
              <Zap size={15} />
              <span>{hook.label}</span>
            </div>
            <strong>{hook.key}</strong>
            <small>{hook.value}</small>
          </div>
        ))}
      </div>
    </section>
  );
}

function RetrievalInspectorPanel({
  latestMessage,
  status,
}: {
  latestMessage: ChatMessage | undefined;
  status: RuntimeStatus | null;
}) {
  const trace = latestMessage?.trace;
  const vectorDetail = traceDetail(trace, "VectorRetrieveTool");
  const rerankDetail = traceDetail(trace, "RerankTool");
  const citationDetail = traceDetail(trace, "CitationBuilderTool");
  const policyDetail = traceDetail(trace, "AnswerPolicyTool");
  const llmDetail = traceDetail(trace, "LlmGenerateTool");
  const confidenceDetail = traceDetail(trace, "ConfidenceGuard");
  const provider = traceValue(vectorDetail, "provider") || status?.llm.embeddingProvider || "waiting";
  const index = traceValue(vectorDetail, "index") || status?.llm.embeddingIndex || "-";
  const dimensions = traceValue(vectorDetail, "dimensions") || String(status?.llm.embeddingDimensions ?? 0);
  const candidates = traceValue(vectorDetail, "candidates") || "-";
  const topCosine = traceValue(vectorDetail, "topCosine");
  const usable = traceValue(vectorDetail, "usable") || "-";
  const topHit = traceValue(vectorDetail, "top") || "-";
  const rerankProvider = traceValue(rerankDetail, "provider") || status?.llm.rerankProvider || "waiting";
  const topK = traceValue(rerankDetail, "topK") || "-";
  const keywordScore = traceValue(rerankDetail, "keywordScore") || "-";
  const rerankScore = traceValue(rerankDetail, "rerankScore") || "-";
  const citations = traceValue(citationDetail, "citations") || String(latestMessage?.citations?.length ?? 0);
  const rejected = traceValue(citationDetail, "rejected") || "0";
  const llmModel = traceValue(llmDetail, "model") || status?.llm.model || "-";
  const durationMs = traceValue(llmDetail, "durationMs");
  const tokens = traceTokenValue(llmDetail) || "-";
  const confidenceLevel = latestMessage?.confidenceLevel || traceValue(confidenceDetail, "level") || "-";
  const confidenceScore = latestMessage?.confidenceScore !== undefined
    ? String(latestMessage.confidenceScore)
    : traceValue(confidenceDetail, "score") || "0";
  const hasTrace = Boolean(trace?.length);
  const retrievalTone = provider.includes("fallback") || !hasTrace ? "warn" : "done";
  const citationTone = Number(citations) > 0 ? "done" : "warn";
  const policy = answerModeLabel(latestMessage?.answerMode) || latestMessage?.answerMode || "waiting";

  return (
    <section className="panel runtime-panel">
      <div className="panel-title">
        <Search size={19} />
        <h2>Retrieval Inspector</h2>
        <span className={`panel-count ${hasTrace ? "ready" : ""}`}>{hasTrace ? "trace" : "waiting"}</span>
      </div>
      {!hasTrace ? (
        <div className="library-empty">Ask Agent once to inspect vector recall, rerank, citation and LLM runtime.</div>
      ) : (
        <div className="runtime-grid">
          <RuntimeCell
            label="Vector"
            tone={retrievalTone}
            value={provider}
            detail={`${index} / ${dimensions} dims`}
          />
          <RuntimeCell
            label="Recall"
            tone={Number(candidates) > 0 ? "done" : "warn"}
            value={`${candidates} candidates`}
            detail={topCosine ? `usable=${usable} / topCosine=${topCosine}` : `usable=${usable}`}
          />
          <RuntimeCell
            label="Rerank"
            tone={Number(topK) > 0 ? "done" : "warn"}
            value={rerankProvider}
            detail={`topK=${topK} / keyword=${keywordScore} / rerank=${rerankScore}`}
          />
          <RuntimeCell
            label="Citation"
            tone={citationTone}
            value={`${citations} citations`}
            detail={`rejected=${rejected} / ${citationDetail || policyDetail || policy}`}
          />
          <RuntimeCell
            label="Top Hit"
            tone={topHit === "-" || topHit === "none" ? "warn" : "done"}
            value={topHit === "-" ? "waiting" : topHit}
            detail="segmentId@videoId/startMs"
          />
          <RuntimeCell
            label="LLM"
            tone={llmModel === "-" ? "warn" : "done"}
            value={llmModel}
            detail={durationMs ? `${durationMs}ms / ${tokens} tokens` : `${tokens} tokens`}
          />
          <RuntimeCell
            label="Confidence"
            tone={confidenceLevel === "HIGH" || confidenceLevel === "MEDIUM" ? "done" : "warn"}
            value={confidenceLevel}
            detail={`score=${confidenceScore}`}
          />
        </div>
      )}
    </section>
  );
}

function VideoLibraryPanel({
  activeVideoId,
  onClose,
  onSelect,
  variant = "inline",
  videos,
}: {
  activeVideoId: number | null;
  onClose?: () => void;
  onSelect: (videoId: number) => void;
  variant?: "inline" | "popover";
  videos: VideoAsset[];
}) {
  return (
    <section className={`panel library-panel ${variant === "popover" ? "library-popover" : ""}`}>
      <div className="panel-title">
        <Database size={19} />
        <h2>视频知识库</h2>
        <span className="panel-count">{videos.length} 个</span>
        {onClose && (
          <button className="panel-action" onClick={onClose} type="button">
            收起
          </button>
        )}
      </div>
      <div className="library-list">
        {videos.length === 0 ? (
          <div className="library-empty">上传后自动加入默认知识库</div>
        ) : (
          videos.map((video) => (
            <button
              className={`library-row ${video.id === activeVideoId ? "active" : ""}`}
              key={video.id}
              onClick={() => onSelect(video.id)}
              type="button"
            >
              <span>{video.originalName}</span>
              <small>video#{video.id} · {video.status} · {formatDuration(video.durationMs)}</small>
            </button>
          ))
        )}
      </div>
    </section>
  );
}

function PipelinePanel({ steps }: { steps: PipelineStep[] }) {
  return (
    <section className="panel pipeline-panel">
      <div className="panel-title">
        <GitBranch size={19} />
        <h2>轻量 DAG</h2>
      </div>
      <ol className="pipeline-list">
        {steps.map((step) => (
          <li className={`pipeline-item ${step.status}`} key={step.label}>
            <span className="status-dot" />
            <div>
              <strong>{step.label}</strong>
              <small>{step.detail}</small>
            </div>
          </li>
        ))}
      </ol>
    </section>
  );
}

function HookPanel({
  job,
  summaries,
  transcripts,
  video,
}: {
  job: ProcessingJob | null;
  summaries: SummaryAsset[];
  transcripts: ApiTranscriptSegment[];
  video: VideoAsset | null;
}) {
  const hooks = [
    {
      icon: <Fingerprint size={16} />,
      label: "MD5 去重",
      keyword: "uk_video_md5",
      value: video ? `${video.md5.slice(0, 8)}...${video.md5.slice(-6)}` : "等待视频",
    },
    {
      icon: <GitBranch size={16} />,
      label: "任务状态机",
      keyword: "optimistic version",
      value: job ? `${job.currentStep} / version ${job.version ?? "-"}` : "等待 job",
    },
    {
      icon: <Database size={16} />,
      label: "时间轴索引",
      keyword: "video_id + start_ms",
      value: transcripts.length ? `${transcripts.length} segments` : "等待字幕",
    },
    {
      icon: <ShieldCheck size={16} />,
      label: "总结资产约束",
      keyword: "uk_summary_video_type",
      value: summaries.length ? `${summaries.length} assets` : "等待总结",
    },
  ];

  return (
    <section className="panel compact-panel">
      <div className="panel-title">
        <Sparkles size={19} />
        <h2>面试钩子</h2>
      </div>
      <div className="hook-list">
        {hooks.map((hook) => (
          <div className="hook-card" key={hook.keyword}>
            <div className="hook-card-head">
              {hook.icon}
              <span>{hook.label}</span>
            </div>
            <strong>{hook.keyword}</strong>
            <small>{hook.value}</small>
          </div>
        ))}
      </div>
    </section>
  );
}

function DatabaseStatePanel({
  job,
  runtime,
  summaries,
  transcripts,
  video,
}: {
  job: ProcessingJob | null;
  runtime: RuntimeStatus | null;
  summaries: SummaryAsset[];
  transcripts: ApiTranscriptSegment[];
  video: VideoAsset | null;
}) {
  const rows = [
    {
      table: "video_asset",
      hook: "uk_video_md5",
      value: video ? `video#${video.id} / v${video.version ?? 0}` : "waiting",
    },
    {
      table: "processing_job",
      hook: "optimistic version",
      value: job ? `${job.status} / v${job.version ?? 0}` : "waiting",
    },
    {
      table: "transcript_segment",
      hook: "idx_video_start_ms",
      value: `${transcripts.length} rows`,
    },
    {
      table: "summary_asset",
      hook: "uk_summary_video_type",
      value: `${summaries.length} rows`,
    },
    {
      table: "chat_message",
      hook: "conversation persistence",
      value: "Agent trace + citation",
    },
  ];

  return (
    <section className="panel compact-panel database-state-panel">
      <div className="panel-title">
        <Database size={19} />
        <h2>MySQL State</h2>
        <span className={`panel-count ${runtime?.database.connected ? "ready" : ""}`}>
          {runtime?.database.product ?? "unknown"}
        </span>
      </div>
      <div className="db-row-list">
        {rows.map((row) => (
          <div className="db-row" key={row.table}>
            <span>{row.table}</span>
            <strong>{row.hook}</strong>
            <small>{row.value}</small>
          </div>
        ))}
      </div>
    </section>
  );
}

function VideoPanel({
  activeTranscript,
  onPlaybackTimeChange,
  playbackMs,
  video,
  videoRef,
}: {
  activeTranscript: ApiTranscriptSegment;
  onPlaybackTimeChange: (currentTimeSeconds: number) => void;
  playbackMs: number;
  video: VideoAsset | null;
  videoRef: React.RefObject<HTMLVideoElement | null>;
}) {
  const mediaUrl = video ? `${API_BASE}/api/videos/${video.id}/media` : "";

  return (
    <section className="video-panel">
      <div className="video-toolbar">
        <div>
          <h2>{video?.originalName ?? "OmniVid Demo Session"}</h2>
          <span>
            {video
              ? `video#${video.id} · ${video.status} · ${formatDuration(video.durationMs)} · 当前 ${formatTime(playbackMs)}`
              : "等待后端视频资产"}
          </span>
        </div>
        <button className="icon-button" type="button" aria-label="播放">
          <Play size={19} fill="currentColor" />
        </button>
      </div>
      <div className="video-surface">
        {video ? (
          <video
            className="video-player"
            controls
            key={video.id}
            onSeeked={(event) => onPlaybackTimeChange(event.currentTarget.currentTime)}
            onTimeUpdate={(event) => onPlaybackTimeChange(event.currentTarget.currentTime)}
            preload="metadata"
            ref={videoRef}
            src={mediaUrl}
          />
        ) : (
          <div className="play-badge">
            <Play size={26} fill="currentColor" />
          </div>
        )}
      </div>
      <div className="video-caption">
        当前引用片段 · {formatTime(activeTranscript.startMs)}
        <strong>{activeTranscript.content}</strong>
      </div>
      <div className="timeline">
        <span style={{ width: video ? "100%" : "12%" }} />
      </div>
    </section>
  );
}

function TranscriptPanel({
  activeSegment,
  onSelect,
  onSearch,
  onSearchQueryChange,
  onSelectSearchResult,
  searchQuery,
  searchResults,
  searching,
  transcripts,
}: {
  activeSegment: number;
  onSelect: (index: number) => void;
  onSearch: (keyword: string) => void;
  onSearchQueryChange: (keyword: string) => void;
  onSelectSearchResult: (segment: ApiTranscriptSegment) => void;
  searchQuery: string;
  searchResults: ApiTranscriptSegment[];
  searching: boolean;
  transcripts: ApiTranscriptSegment[];
}) {
  const rows = transcripts.length ? transcripts : [fallbackTranscript];
  const canSearch = transcripts.length > 0;

  return (
    <section className="panel transcript-panel">
      <div className="panel-title">
        <Clock3 size={19} />
        <h2>时间轴字幕</h2>
        <span className="panel-count">{transcripts.length ? `${transcripts.length} 条已加载` : "等待上传"}</span>
      </div>
      <form
        className="transcript-search"
        onSubmit={(event) => {
          event.preventDefault();
          onSearch(searchQuery);
        }}
      >
        <Search size={16} />
        <input
          disabled={!canSearch}
          onChange={(event) => onSearchQueryChange(event.target.value)}
          placeholder={canSearch ? "搜索字幕关键词" : "等待字幕生成"}
          value={searchQuery}
        />
        <button disabled={!canSearch || !searchQuery.trim() || searching} type="submit">
          {searching ? "检索中" : "搜索"}
        </button>
      </form>
      {searchQuery.trim() && (
        <div className="search-results">
          <div className="search-results-head">
            <span>{searchResults.length ? `命中 ${searchResults.length} 条` : "暂无命中"}</span>
            <small>点击结果跳转原视频时间点</small>
          </div>
          {searchResults.map((segment) => (
            <button
              className="search-result-row"
              key={`search-${segment.id}`}
              onClick={() => onSelectSearchResult(segment)}
              type="button"
            >
              <span className="time-code">{formatTime(segment.startMs)}</span>
              <span>{segment.content}</span>
              <ChevronRight size={16} />
            </button>
          ))}
        </div>
      )}
      <div className="transcript-list">
        {rows.map((segment, index) => (
          <button
            className={`transcript-row ${index === activeSegment ? "active" : ""}`}
            key={`${segment.id}-${segment.startMs}`}
            onClick={() => onSelect(index)}
            type="button"
          >
            <span className="time-code">{formatTime(segment.startMs)}</span>
            <span className="speaker">{segment.speaker}</span>
            <span className="transcript-text">{segment.content}</span>
            <ChevronRight size={17} />
          </button>
        ))}
      </div>
    </section>
  );
}

function RightWorkspacePanel({
  activeTab,
  agent,
  onTabChange,
  summariesCount,
  summary,
}: {
  activeTab: RightWorkspaceTab;
  agent: React.ReactNode;
  onTabChange: (tab: RightWorkspaceTab) => void;
  summariesCount: number;
  summary: React.ReactNode;
}) {
  return (
    <section className="right-workspace-panel" aria-label="右侧工作区">
      <div className="right-workspace-switch" aria-label="右侧模块选择">
        <button
          className={activeTab === "summary" ? "active" : ""}
          onClick={() => onTabChange("summary")}
          type="button"
        >
          <FileText size={16} />
          <span>结构化总结</span>
          <small>{summariesCount ? `${summariesCount} 份` : "等待"}</small>
        </button>
        <button
          className={activeTab === "agent" ? "active" : ""}
          onClick={() => onTabChange("agent")}
          type="button"
        >
          <MessageSquareText size={16} />
          <span>Agent 问答</span>
          <small>对话</small>
        </button>
      </div>
      <div className="right-workspace-body">
        {activeTab === "summary" ? summary : agent}
      </div>
    </section>
  );
}

function SummaryPanel({ summaries }: { summaries: SummaryAsset[] }) {
  const [activeType, setActiveType] = useState("CORE_POINTS");
  const [generationStatus, setGenerationStatus] = useState("");
  const activeSummary = summaries.find((summary) => summary.type === activeType) ?? summaries[0];
  const activeItems = parseSummary(activeSummary);
  const activeTemplate = summaryTemplates.find((template) => template.type === activeType) ?? summaryTemplates[0];

  function handleGenerateAsset() {
    setGenerationStatus(`${activeTemplate.actionLabel}已准备，后续可接入大模型生成任务。`);
  }

  return (
    <section className="panel summary-panel">
      <div className="panel-title">
        <FileText size={19} />
        <h2>结构化总结</h2>
        <span className="panel-count">{summaries.length ? `${summaries.length} 份已加载` : "等待上传"}</span>
      </div>
      <div className="summary-tabs" aria-label="总结模板">
        {summaryTemplates.map((template) => {
          const available = summaries.some((summary) => summary.type === template.type);
          return (
            <button
              className={activeType === template.type ? "active" : ""}
              disabled={!available}
              key={template.type}
              onClick={() => {
                setActiveType(template.type);
                setGenerationStatus("");
              }}
              type="button"
            >
              {template.label}
            </button>
          );
        })}
      </div>
      <div className="summary-block">
        <span>{activeSummary?.title ?? "等待后端总结"}</span>
        {activeItems.length ? (
          <ol className="summary-items">
            {activeItems.map((item) => (
              <li key={item}>{item}</li>
            ))}
          </ol>
        ) : (
          <p>点击上传后，这里会展示 /api/videos/:id/summaries 返回的总结资产。</p>
        )}
      </div>
      <button className="text-button summary-generate-button" disabled={!activeSummary} onClick={handleGenerateAsset} type="button">
        {activeTemplate.actionLabel}
        <ArrowUpRight size={16} />
      </button>
      {generationStatus && <p className="summary-generation-status">{generationStatus}</p>}
    </section>
  );
}

function KnowledgeBaseManagerPanel({
  activeDetail,
  activeId,
  form,
  isSaving,
  knowledgeBases,
  onAddVideo,
  onChangeForm,
  onComparePrompt,
  onCreate,
  onDelete,
  onRemoveVideo,
  onSelect,
  pendingVideoId,
  status,
  videos,
}: {
  activeDetail: KnowledgeBaseDetail | null;
  activeId: number | null;
  form: KnowledgeBaseFormState;
  isSaving: boolean;
  knowledgeBases: KnowledgeBase[];
  onAddVideo: (videoId: number) => void;
  onChangeForm: (form: KnowledgeBaseFormState) => void;
  onComparePrompt: () => void;
  onCreate: () => void;
  onDelete: () => void;
  onRemoveVideo: (videoId: number) => void;
  onSelect: (knowledgeBaseId: number) => void;
  pendingVideoId: number | null;
  status: string;
  videos: VideoAsset[];
}) {
  const memberIds = new Set(activeDetail?.videos.map((video) => video.id) ?? []);
  const activeCount = activeDetail?.videos.length ?? 0;

  return (
    <div className="knowledge-manager">
      <div className="knowledge-manager-head">
        <div>
          <strong>知识库管理</strong>
          <small>{activeId ? `${activeCount} 个视频参与聚合问答` : "未选择时使用默认全量知识库"}</small>
        </div>
        <button disabled={!activeId || activeCount < 2} onClick={onComparePrompt} type="button">
          对比观点
        </button>
        <button className="danger" disabled={!activeId} onClick={onDelete} type="button">
          删除
        </button>
      </div>
      <div className="knowledge-create-row">
        <input
          aria-label="知识库名称"
          onChange={(event) => onChangeForm({ ...form, name: event.currentTarget.value })}
          placeholder="知识库名称"
          value={form.name}
        />
        <button disabled={isSaving || !form.name.trim()} onClick={onCreate} type="button">
          {isSaving ? "创建中" : "创建"}
        </button>
      </div>
      <input
        aria-label="知识库描述"
        className="knowledge-description-input"
        onChange={(event) => onChangeForm({ ...form, description: event.currentTarget.value })}
        placeholder="描述，可选"
        value={form.description}
      />
      <div className="knowledge-base-tabs" aria-label="知识库列表">
        {knowledgeBases.length === 0 ? (
          <span>还没有自定义知识库</span>
        ) : (
          knowledgeBases.map((item) => (
            <button
              className={item.id === activeId ? "active" : ""}
              key={item.id}
              onClick={() => onSelect(item.id)}
              type="button"
            >
              <strong>{item.name}</strong>
              <small>{item.videoCount}</small>
            </button>
          ))
        )}
      </div>
      {activeId && (
        <div className="knowledge-video-list" aria-label="知识库视频成员">
          {videos.length === 0 ? (
            <span>视频库为空</span>
          ) : (
            videos.map((video) => {
              const included = memberIds.has(video.id);
              return (
                <button
                  className={included ? "included" : ""}
                  disabled={pendingVideoId === video.id}
                  key={video.id}
                  onClick={() => included ? onRemoveVideo(video.id) : onAddVideo(video.id)}
                  type="button"
                >
                  <span>{video.originalName}</span>
                  <small>{included ? "已加入" : "加入"} · video#{video.id}</small>
                </button>
              );
            })
          )}
        </div>
      )}
      {status && <p className={`knowledge-status ${status.startsWith("已") || status.includes("已") ? "success" : ""}`}>{status}</p>}
    </div>
  );
}

function AgentPanel({
  activeKnowledgeBaseDetail,
  activeKnowledgeBaseId,
  disabled,
  context,
  isSavingKnowledgeBase,
  knowledgeBaseForm,
  knowledgeBases,
  knowledgeBaseStatus,
  mode,
  messages,
  onAddVideoToKnowledgeBase,
  onClear,
  onCitationSelect,
  onCreateKnowledgeBase,
  onDeleteKnowledgeBase,
  onKnowledgeBaseFormChange,
  query,
  video,
  onModeChange,
  onQueryChange,
  onRemoveVideoFromKnowledgeBase,
  onSelectKnowledgeBase,
  onAsk,
  pendingKnowledgeBaseVideoId,
  videos,
}: {
  activeKnowledgeBaseDetail: KnowledgeBaseDetail | null;
  activeKnowledgeBaseId: number | null;
  disabled: boolean;
  context: AgentContext | null;
  isSavingKnowledgeBase: boolean;
  knowledgeBaseForm: KnowledgeBaseFormState;
  knowledgeBases: KnowledgeBase[];
  knowledgeBaseStatus: string;
  mode: AgentMode;
  messages: ChatMessage[];
  onAddVideoToKnowledgeBase: (videoId: number) => void;
  onClear: () => void;
  onCitationSelect: (citation: AgentCitation) => void;
  onCreateKnowledgeBase: () => void;
  onDeleteKnowledgeBase: () => void;
  onKnowledgeBaseFormChange: (form: KnowledgeBaseFormState) => void;
  query: string;
  video: VideoAsset | null;
  onModeChange: (mode: AgentMode) => void;
  onQueryChange: (value: string) => void;
  onRemoveVideoFromKnowledgeBase: (videoId: number) => void;
  onSelectKnowledgeBase: (knowledgeBaseId: number) => void;
  onAsk: () => void;
  pendingKnowledgeBaseVideoId: number | null;
  videos: VideoAsset[];
}) {
  const activeKnowledgeBaseName = activeKnowledgeBaseDetail?.knowledgeBase.name
    ?? knowledgeBases.find((item) => item.id === activeKnowledgeBaseId)?.name
    ?? "默认全量知识库";

  return (
    <section className="panel agent-panel">
      <div className="panel-title">
        <MessageSquareText size={19} />
        <h2>Agent 问答</h2>
        <button
          className="panel-action"
          disabled={!video || messages.length <= 1}
          onClick={onClear}
          type="button"
        >
          清空
        </button>
      </div>
      <div className="agent-mode" aria-label="问答范围">
        <button
          className={mode === "video" ? "active" : ""}
          onClick={() => onModeChange("video")}
          type="button"
        >
          当前视频
        </button>
        <button
          className={mode === "knowledgeBase" ? "active" : ""}
          onClick={() => onModeChange("knowledgeBase")}
          type="button"
        >
          知识库
        </button>
      </div>
      {mode === "knowledgeBase" && (
        <KnowledgeBaseManagerPanel
          activeDetail={activeKnowledgeBaseDetail}
          activeId={activeKnowledgeBaseId}
          form={knowledgeBaseForm}
          isSaving={isSavingKnowledgeBase}
          knowledgeBases={knowledgeBases}
          onAddVideo={onAddVideoToKnowledgeBase}
          onChangeForm={onKnowledgeBaseFormChange}
          onComparePrompt={() => onQueryChange("对比这个知识库中多个视频的核心观点差异")}
          onCreate={onCreateKnowledgeBase}
          onDelete={onDeleteKnowledgeBase}
          onRemoveVideo={onRemoveVideoFromKnowledgeBase}
          onSelect={onSelectKnowledgeBase}
          pendingVideoId={pendingKnowledgeBaseVideoId}
          status={knowledgeBaseStatus}
          videos={videos}
        />
      )}
      <div className="agent-context-card">
        <span>
          {mode === "knowledgeBase" ? activeKnowledgeBaseName : "当前视频"} · 上下文窗口 {context ? `${context.messageCount}/${context.windowLimit}` : "0/6"}
        </span>
        <small>
          {context?.contextReady
            ? `${context.memorySource} 短期记忆：${context.shortTermQuestion || context.lastUserQuestion}`
            : video
              ? "还没有可用的上一轮问题"
              : "等待选择视频"}
        </small>
      </div>
      <div className="chat-list">
        {messages.map((message, index) => (
          <div className={`chat-bubble ${message.role}`} key={`${message.role}-${index}`}>
            <p>{message.text}</p>
            {message.citation && (!message.citations || message.citations.length <= 1) && (
              <button
                className="citation"
                disabled={!message.citations?.[0]}
                onClick={() => {
                  if (message.citations?.[0]) {
                    onCitationSelect(message.citations[0]);
                  }
                }}
                type="button"
              >
                <Search size={15} />
                {message.citation}
              </button>
            )}
            {message.citations && message.citations.length > 1 && (
              <div className="citation-list" aria-label="多证据引用">
                {message.citations.map((citation) => (
                  <button
                    className="citation-chip"
                    key={`${citation.videoId}-${citation.segmentId}`}
                    onClick={() => onCitationSelect(citation)}
                    type="button"
                  >
                    <Search size={13} />
                    <strong>{citation.citation}</strong>
                    <small>{citation.snippet}</small>
                  </button>
                ))}
              </div>
            )}
            {message.role === "agent" &&
              (message.trace?.length ||
                answerModeLabel(message.answerMode) ||
                message.confidenceLevel ||
                message.cacheHit ||
                message.contextUsed) && (
                <details className="agent-trace-disclosure">
                  <summary>
                    <GitBranch size={13} />
                    <span>展开执行链路</span>
                    <small>{message.trace?.length ? `${message.trace.length} steps` : "summary"}</small>
                  </summary>
            {answerModeLabel(message.answerMode) && (
              <span className={`answer-mode ${answerModeClass(message.answerMode)}`}>
                {answerModeLabel(message.answerMode)}
              </span>
            )}
            {message.cacheHit && <span className="cache-hit">cache hit</span>}
            {message.contextUsed && <span className="context-used">多轮上下文</span>}
            {message.confidenceLevel && (
              <span className={`confidence-chip ${message.confidenceLevel.toLowerCase()}`}>
                {message.confidenceLevel} · {message.confidenceScore ?? 0}
              </span>
            )}
            {runtimeBadges(message.trace).length > 0 && (
              <div className="runtime-badges" aria-label="Agent runtime summary">
                {runtimeBadges(message.trace).map((badge) => (
                  <span className={`runtime-badge ${badge.tone}`} key={badge.label}>
                    <strong>{badge.label}</strong>
                    <small>{badge.value}</small>
                  </span>
                ))}
              </div>
            )}
            {message.trace && message.trace.length > 0 && (
              <div className="trace-list" aria-label="Agent 执行轨迹">
                {message.trace.map((step, stepIndex) => (
                  <span
                    className={`trace-chip ${step.status.toLowerCase()}`}
                    key={`${step.name}-${stepIndex}`}
                  >
                    <GitBranch size={12} />
                    <strong>{step.name}</strong>
                    <small>{step.detail}</small>
                  </span>
                ))}
              </div>
            )}
                </details>
              )}
          </div>
        ))}
      </div>
      <form
        className="chat-input"
        onSubmit={(event) => {
          event.preventDefault();
          onAsk();
        }}
      >
        <input
          aria-label="向 Agent 提问"
          disabled={disabled}
          onChange={(event) => onQueryChange(event.target.value)}
          placeholder={disabled ? "先点击上传接通后端视频资产" : "追问：任务状态乱序怎么办？"}
          value={query}
        />
        <button disabled={disabled} type="submit" aria-label="发送">
          <Send size={18} />
        </button>
      </form>
    </section>
  );
}

createRoot(document.getElementById("root")!).render(<App />);
