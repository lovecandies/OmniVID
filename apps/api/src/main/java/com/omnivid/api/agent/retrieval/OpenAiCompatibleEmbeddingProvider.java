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
    private final ProviderConfig defaultConfig;
    private final ThreadLocal<ProviderConfig> scopedConfig = new ThreadLocal<>();
    private final ThreadLocal<ProviderState> scopedState;

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
        String normalizedMode = normalizeMode(mode);
        this.defaultConfig = new ProviderConfig(
                enabled,
                normalizedMode,
                apiKey == null ? "" : apiKey.trim(),
                normalizeBaseUrl(baseUrl, normalizedMode),
                normalizeModel(model, normalizedMode),
                timeout
        );
        this.scopedState = ThreadLocal.withInitial(() -> ProviderState.initial(fallback.dimensions()));
    }

    public void configure(
            boolean enabled,
            String mode,
            String apiKey,
            String baseUrl,
            String model,
            int timeoutSeconds
    ) {
        String normalizedMode = normalizeMode(mode);
        scopedConfig.set(new ProviderConfig(
                enabled,
                normalizedMode,
                apiKey == null ? "" : apiKey.trim(),
                normalizeBaseUrl(baseUrl, normalizedMode),
                normalizeModel(model, normalizedMode),
                Duration.ofSeconds(Math.min(120, Math.max(5, timeoutSeconds)))
        ));
        scopedState.set(ProviderState.initial(fallback.dimensions()));
    }

    @Override
    public String providerName() {
        ProviderConfig config = currentConfig();
        ProviderState state = scopedState.get();
        if (!cloudConfigured(config)) {
            return fallback.providerName();
        }
        if (state.lastCloudSuccess()) {
            return config.mode() + ":" + config.model();
        }
        return config.mode() + "-pending";
    }

    @Override
    public int dimensions() {
        ProviderState state = scopedState.get();
        return state.lastCloudSuccess() ? state.cloudDimensions() : fallback.dimensions();
    }

    @Override
    public Map<Integer, Double> embed(String text) {
        if (text == null || text.isBlank()) {
            return Map.of();
        }
        ProviderConfig config = currentConfig();
        if (!cloudConfigured(config)) {
            scopedState.set(ProviderState.initial(fallback.dimensions()));
            return fallback.embed(text);
        }
        try {
            String payload = objectMapper.writeValueAsString(Map.of(
                    "model", config.model(),
                    "input", text
            ));
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(config.baseUrl() + "/embeddings"))
                    .timeout(config.timeout())
                    .header("Content-Type", "application/json");
            if (!config.apiKey().isBlank()) {
                builder.header("Authorization", "Bearer " + config.apiKey());
            }
            HttpResponse<String> response = httpClient.send(
                    builder.POST(HttpRequest.BodyPublishers.ofString(payload)).build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                scopedState.set(new ProviderState(
                        false,
                        fallback.dimensions(),
                        "embedding endpoint returned HTTP " + response.statusCode()
                ));
                return fallback.embed(text);
            }

            JsonNode embedding = objectMapper.readTree(response.body()).path("data").path(0).path("embedding");
            if (!embedding.isArray() || embedding.isEmpty()) {
                scopedState.set(new ProviderState(
                        false,
                        fallback.dimensions(),
                        "embedding response did not contain data[0].embedding"
                ));
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
            scopedState.set(new ProviderState(true, index, ""));
            return vector;
        } catch (Exception exception) {
            scopedState.set(new ProviderState(false, fallback.dimensions(), exception.getMessage()));
            return fallback.embed(text);
        }
    }

    @Override
    public boolean cloudBacked() {
        return scopedState.get().lastCloudSuccess();
    }

    @Override
    public String diagnostic() {
        ProviderConfig config = currentConfig();
        ProviderState state = scopedState.get();
        if (!cloudConfigured(config)) {
            return "Embedding mode is local; using local hash fallback";
        }
        if (state.lastCloudSuccess()) {
            return "OpenAI-compatible embedding active: mode=" + config.mode()
                    + ", model=" + config.model()
                    + ", dims=" + state.cloudDimensions();
        }
        if (state.lastFailure() == null || state.lastFailure().isBlank()) {
            return "Embedding provider configured but not proven yet: mode=" + config.mode()
                    + ", model=" + config.model()
                    + ", baseUrl=" + config.baseUrl();
        }
        return "Embedding provider unavailable: " + state.lastFailure() + "; using local hash fallback";
    }

    public void clearScopedConfig() {
        scopedConfig.remove();
        scopedState.remove();
    }

    private ProviderConfig currentConfig() {
        ProviderConfig config = scopedConfig.get();
        return config == null ? defaultConfig : config;
    }

    private boolean cloudConfigured(ProviderConfig config) {
        if (!config.enabled() || "local".equals(config.mode())) {
            return false;
        }
        return "bge".equals(config.mode()) || !config.apiKey().isBlank();
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

    private record ProviderConfig(
            boolean enabled,
            String mode,
            String apiKey,
            String baseUrl,
            String model,
            Duration timeout
    ) {
    }

    private record ProviderState(boolean lastCloudSuccess, int cloudDimensions, String lastFailure) {
        private static ProviderState initial(int dimensions) {
            return new ProviderState(false, dimensions, "");
        }
    }
}
