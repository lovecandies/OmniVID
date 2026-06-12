package com.omnivid.api.agent.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnivid.api.transcript.TranscriptSegment;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class AgentRerankService {
    private static final Pattern LATIN_TOKEN = Pattern.compile("[a-z0-9]+");
    private static final Pattern CJK_BLOCK = Pattern.compile("[\\u4e00-\\u9fa5]+");

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private volatile boolean enabled;
    private volatile String mode;
    private volatile String baseUrl;
    private volatile String endpoint;
    private volatile String apiKey;
    private volatile String model;
    private volatile Duration timeout;
    private volatile boolean lastRemoteSuccess;
    private volatile String lastFailure = "";

    public AgentRerankService(
            ObjectMapper objectMapper,
            @Value("${omnivid.rerank.enabled:true}") boolean enabled,
            @Value("${omnivid.rerank.mode:local}") String mode,
            @Value("${omnivid.rerank.base-url:}") String baseUrl,
            @Value("${omnivid.rerank.endpoint:/rerank}") String endpoint,
            @Value("${omnivid.rerank.api-key:}") String apiKey,
            @Value("${omnivid.rerank.model:bge-reranker-v2-m3}") String model,
            @Value("${omnivid.rerank.timeout:15s}") Duration timeout
    ) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        this.enabled = enabled;
        this.mode = normalizeMode(mode);
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.endpoint = normalizeEndpoint(endpoint);
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model == null || model.isBlank() ? "bge-reranker-v2-m3" : model.trim();
        this.timeout = timeout;
    }

    public synchronized void configure(
            boolean enabled,
            String mode,
            String baseUrl,
            String endpoint,
            String apiKey,
            String model,
            int timeoutSeconds
    ) {
        this.enabled = enabled;
        this.mode = normalizeMode(mode);
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.endpoint = normalizeEndpoint(endpoint);
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model == null || model.isBlank() ? "bge-reranker-v2-m3" : model.trim();
        this.timeout = Duration.ofSeconds(Math.min(120, Math.max(3, timeoutSeconds)));
        this.lastRemoteSuccess = false;
        this.lastFailure = "";
    }

    public List<RerankedCandidate> rerank(String query, List<RerankInput> inputs, int limit) {
        if (inputs.isEmpty()) {
            return List.of();
        }
        if (remoteConfigured()) {
            List<RerankedCandidate> remote = remoteRerank(query, inputs, limit);
            if (!remote.isEmpty()) {
                lastRemoteSuccess = true;
                lastFailure = "";
                return remote;
            }
        }
        lastRemoteSuccess = false;
        return localRerank(query, inputs, limit);
    }

    public String providerName() {
        if (lastRemoteSuccess) {
            return mode + ":" + model;
        }
        return enabled ? "local-rerank" : "rerank-disabled";
    }

    public boolean remoteActive() {
        return lastRemoteSuccess;
    }

    public String diagnostic() {
        if (lastRemoteSuccess) {
            return "Remote rerank active: " + baseUrl + endpoint + ", mode=" + mode + ", model=" + model;
        }
        if (!enabled) {
            return "rerank disabled";
        }
        if (!"local".equals(mode) && !lastFailure.isBlank()) {
            return "Remote rerank unavailable: " + lastFailure + "; using local rerank";
        }
        if (!"local".equals(mode) && !baseUrl.isBlank()) {
            return "Remote rerank configured but not proven yet: " + baseUrl + endpoint + ", model=" + model;
        }
        return "local rerank active";
    }

    private List<RerankedCandidate> remoteRerank(String query, List<RerankInput> inputs, int limit) {
        try {
            List<String> documents = inputs.stream()
                    .map(input -> input.segment().content())
                    .toList();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", model);
            payload.put("query", query);
            payload.put("documents", documents);
            payload.put("top_n", Math.max(limit, 1));

            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + endpoint))
                    .timeout(timeout)
                    .header("Content-Type", "application/json");
            if (!apiKey.isBlank()) {
                builder.header("Authorization", "Bearer " + apiKey);
            }
            HttpResponse<String> response = httpClient.send(
                    builder.POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload))).build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                lastFailure = "HTTP " + response.statusCode();
                return List.of();
            }
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode results = root.path("results").isArray() ? root.path("results") : root.path("data");
            if (!results.isArray()) {
                lastFailure = "response missing results/data array";
                return List.of();
            }
            Map<Integer, Double> remoteScores = new HashMap<>();
            for (JsonNode item : results) {
                int index = item.path("index").asInt(item.path("document").path("index").asInt(-1));
                double score = item.path("relevance_score").asDouble(item.path("score").asDouble(Double.NaN));
                if (index >= 0 && index < inputs.size() && !Double.isNaN(score)) {
                    remoteScores.put(index, score);
                }
            }
            if (remoteScores.isEmpty()) {
                lastFailure = "response did not contain rerank scores";
                return List.of();
            }
            List<RerankedCandidate> reranked = new ArrayList<>();
            for (int index = 0; index < inputs.size(); index++) {
                RerankInput input = inputs.get(index);
                double remoteScore = remoteScores.getOrDefault(index, 0.0);
                reranked.add(new RerankedCandidate(input, clamp(remoteScore)));
            }
            return reranked.stream()
                    .sorted(Comparator.comparingDouble(RerankedCandidate::rerankScore).reversed()
                            .thenComparing(candidate -> candidate.input().segment().startMs()))
                    .limit(limit)
                    .toList();
        } catch (Exception exception) {
            lastFailure = exception.getMessage();
            return List.of();
        }
    }

    private List<RerankedCandidate> localRerank(String query, List<RerankInput> inputs, int limit) {
        Set<String> queryTokens = tokens(query);
        return inputs.stream()
                .map(input -> new RerankedCandidate(input, localScore(queryTokens, input)))
                .sorted(Comparator.comparingDouble(RerankedCandidate::rerankScore).reversed()
                        .thenComparing(candidate -> candidate.input().segment().startMs()))
                .limit(limit)
                .toList();
    }

    private double localScore(Set<String> queryTokens, RerankInput input) {
        Set<String> contentTokens = tokens(input.segment().content());
        int overlap = 0;
        for (String token : queryTokens) {
            if (contentTokens.contains(token)) {
                overlap++;
            }
        }
        double overlapScore = queryTokens.isEmpty() ? 0 : (double) overlap / queryTokens.size();
        double keywordScore = Math.min(1.0, input.keywordScore() / 6.0);
        double vectorScore = clamp(input.vectorScore());
        return clamp(vectorScore * 0.58 + keywordScore * 0.27 + overlapScore * 0.15);
    }

    private Set<String> tokens(String text) {
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT);
        Set<String> tokens = new HashSet<>();
        Matcher latin = LATIN_TOKEN.matcher(normalized);
        while (latin.find()) {
            String token = latin.group();
            if (token.length() >= 2) {
                tokens.add(token);
            }
        }
        Matcher cjk = CJK_BLOCK.matcher(normalized);
        while (cjk.find()) {
            String block = cjk.group();
            if (block.length() <= 6) {
                tokens.add(block);
            }
            for (int index = 0; index < block.length() - 1; index++) {
                tokens.add(block.substring(index, index + 2));
            }
        }
        return tokens;
    }

    private double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0;
        }
        return Math.max(0, Math.min(1, value));
    }

    private String normalizeMode(String value) {
        String normalized = value == null ? "local" : value.trim().toLowerCase(Locale.ROOT);
        if (List.of("bge", "openai-compatible").contains(normalized)) {
            return normalized;
        }
        return "local";
    }

    private String normalizeBaseUrl(String value) {
        String normalized = value == null ? "" : value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String normalizeEndpoint(String value) {
        String normalized = value == null || value.isBlank() ? "/rerank" : value.trim();
        return normalized.startsWith("/") ? normalized : "/" + normalized;
    }

    private boolean remoteConfigured() {
        return enabled && !"local".equals(mode) && !baseUrl.isBlank();
    }

    public record RerankInput(
            TranscriptSegment segment,
            double vectorScore,
            int keywordScore,
            int baseScore
    ) {
    }

    public record RerankedCandidate(RerankInput input, double rerankScore) {
    }
}
