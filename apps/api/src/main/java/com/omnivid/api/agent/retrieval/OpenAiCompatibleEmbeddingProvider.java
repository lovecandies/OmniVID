package com.omnivid.api.agent.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

@Primary
@Service
public class OpenAiCompatibleEmbeddingProvider implements EmbeddingProvider {
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final LocalHashEmbeddingProvider fallback;
    private volatile String mode;
    private volatile boolean enabled;
    private volatile String apiKey;
    private volatile String baseUrl;
    private volatile String model;
    private volatile Duration timeout;
    private volatile boolean lastCloudSuccess;
    private volatile int cloudDimensions;
    private volatile String lastFailure = "";

    public OpenAiCompatibleEmbeddingProvider(
            ObjectMapper objectMapper,
            LocalHashEmbeddingProvider fallback,
            @Value("${omnivid.embedding.mode:local}") String mode,
            @Value("${omnivid.embedding.enabled:false}") boolean enabled,
            @Value("${omnivid.embedding.api-key:}") String apiKey,
            @Value("${omnivid.embedding.base-url:}") String baseUrl,
            @Value("${omnivid.embedding.model:}") String model,
            @Value("${omnivid.embedding.timeout:30s}") Duration timeout
    ) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.fallback = fallback;
        this.mode = normalizeMode(mode);
        this.enabled = enabled;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.baseUrl = normalizeBaseUrl(baseUrl, this.mode);
        this.model = normalizeModel(model, this.mode);
        this.timeout = timeout;
        this.cloudDimensions = fallback.dimensions();
    }

    public synchronized void configure(
            boolean enabled,
            String mode,
            String apiKey,
            String baseUrl,
            String model,
            int timeoutSeconds
    ) {
        this.mode = normalizeMode(mode);
        this.enabled = enabled;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.baseUrl = normalizeBaseUrl(baseUrl, this.mode);
        this.model = normalizeModel(model, this.mode);
        this.timeout = Duration.ofSeconds(Math.min(120, Math.max(5, timeoutSeconds)));
        this.lastCloudSuccess = false;
        this.cloudDimensions = fallback.dimensions();
        this.lastFailure = "";
    }

    @Override
    public String providerName() {
        if (!cloudConfigured()) {
            return fallback.providerName();
        }
        if (lastCloudSuccess) {
            return mode + ":" + model;
        }
        return mode + "-pending";
    }

    @Override
    public int dimensions() {
        return lastCloudSuccess ? cloudDimensions : fallback.dimensions();
    }

    @Override
    public Map<Integer, Double> embed(String text) {
        if (text == null || text.isBlank()) {
            return Map.of();
        }
        if (!cloudConfigured()) {
            lastCloudSuccess = false;
            return fallback.embed(text);
        }
        try {
            String payload = objectMapper.writeValueAsString(Map.of(
                    "model", model,
                    "input", text
            ));
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + "/embeddings"))
                    .timeout(timeout)
                    .header("Content-Type", "application/json");
            if (!apiKey.isBlank()) {
                builder.header("Authorization", "Bearer " + apiKey);
            }
            HttpResponse<String> response = httpClient.send(
                    builder.POST(HttpRequest.BodyPublishers.ofString(payload)).build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                lastCloudSuccess = false;
                lastFailure = "embedding endpoint returned HTTP " + response.statusCode();
                return fallback.embed(text);
            }

            JsonNode embedding = objectMapper.readTree(response.body()).path("data").path(0).path("embedding");
            if (!embedding.isArray() || embedding.isEmpty()) {
                lastCloudSuccess = false;
                lastFailure = "embedding response did not contain data[0].embedding";
                return fallback.embed(text);
            }
            Map<Integer, Double> vector = new HashMap<>();
            int index = 0;
            for (JsonNode value : embedding) {
                double number = value.asDouble();
                if (number != 0) {
                    vector.put(index, number);
                }
                index++;
            }
            cloudDimensions = index;
            lastCloudSuccess = true;
            lastFailure = "";
            return vector;
        } catch (Exception exception) {
            lastCloudSuccess = false;
            lastFailure = exception.getMessage();
            return fallback.embed(text);
        }
    }

    @Override
    public boolean cloudBacked() {
        return lastCloudSuccess;
    }

    @Override
    public String diagnostic() {
        if (!cloudConfigured()) {
            return "Embedding mode is local; using local hash fallback";
        }
        if (lastCloudSuccess) {
            return "OpenAI-compatible embedding active: mode=" + mode
                    + ", model=" + model
                    + ", dims=" + cloudDimensions;
        }
        if (lastFailure == null || lastFailure.isBlank()) {
            return "Embedding provider configured but not proven yet: mode=" + mode
                    + ", model=" + model
                    + ", baseUrl=" + baseUrl;
        }
        return "Embedding provider unavailable: " + lastFailure + "; using local hash fallback";
    }

    private boolean cloudConfigured() {
        if (!enabled || "local".equals(mode)) {
            return false;
        }
        return "bge".equals(mode) || !apiKey.isBlank();
    }

    private String normalizeMode(String value) {
        String normalized = value == null ? "local" : value.trim().toLowerCase(Locale.ROOT);
        if (List.of("openai", "qwen", "bge", "openai-compatible", "local").contains(normalized)) {
            return "openai-compatible".equals(normalized) ? "openai" : normalized;
        }
        return "local";
    }

    private String normalizeBaseUrl(String value, String currentMode) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            normalized = switch (currentMode) {
                case "openai" -> "https://api.openai.com/v1";
                case "qwen" -> "https://dashscope.aliyuncs.com/compatible-mode/v1";
                case "bge" -> "http://localhost:8000/v1";
                default -> "";
            };
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String normalizeModel(String value, String currentMode) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.isBlank()) {
            return normalized;
        }
        return switch (currentMode) {
            case "openai" -> "text-embedding-3-small";
            case "qwen" -> "text-embedding-v4";
            case "bge" -> "BAAI/bge-m3";
            default -> "local-hash";
        };
    }
}
