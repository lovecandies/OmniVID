package com.omnivid.api.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class CloudLlmClient {
    private static final Logger log = LoggerFactory.getLogger(CloudLlmClient.class);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final ClientConfig defaultConfig;
    private final ThreadLocal<ClientConfig> scopedConfig = new ThreadLocal<>();
    private final ThreadLocal<String> lastEmbeddingFailure = ThreadLocal.withInitial(() -> "");
    private final AtomicInteger configVersion = new AtomicInteger();

    public CloudLlmClient(
            ObjectMapper objectMapper,
            @Value("${omnivid.llm.enabled:false}") boolean enabled,
            @Value("${omnivid.llm.api-key:}") String apiKey,
            @Value("${omnivid.llm.base-url:https://api.deepseek.com/v1}") String baseUrl,
            @Value("${omnivid.llm.model:deepseek-chat}") String model,
            @Value("${omnivid.llm.timeout:60s}") Duration timeout
    ) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.defaultConfig = new ClientConfig(
                enabled,
                apiKey == null ? "" : apiKey.trim(),
                normalizedBaseUrl(baseUrl),
                model,
                timeout
        );
    }

    public boolean available() {
        ClientConfig config = currentConfig();
        return config.enabled() && !config.apiKey().isBlank();
    }

    public String model() {
        return currentConfig().model();
    }

    public int configVersion() {
        return configVersion.get();
    }

    public String lastEmbeddingFailure() {
        return lastEmbeddingFailure.get();
    }

    public CloudLlmConfigResponse status() {
        ClientConfig config = currentConfig();
        return new CloudLlmConfigResponse(
                config.enabled(),
                !config.apiKey().isBlank(),
                config.baseUrl(),
                config.model(),
                Math.toIntExact(config.timeout().toSeconds()),
                maskedApiKey(config.apiKey())
        );
    }

    public CloudLlmConfigResponse configure(CloudLlmConfigRequest request) {
        ClientConfig current = currentConfig();
        boolean enabled = request.enabled();
        String apiKey = current.apiKey();
        String baseUrl = current.baseUrl();
        String model = current.model();
        Duration timeout = current.timeout();
        if (request.apiKey() != null && !request.apiKey().isBlank()) {
            apiKey = request.apiKey().trim();
        }
        if (request.apiKey() != null && request.apiKey().isBlank() && !request.enabled()) {
            apiKey = "";
        }
        if (request.baseUrl() != null && !request.baseUrl().isBlank()) {
            baseUrl = normalizedBaseUrl(request.baseUrl());
        }
        if (request.model() != null && !request.model().isBlank()) {
            model = request.model().trim();
        }
        if (request.timeoutSeconds() != null && request.timeoutSeconds() > 0) {
            timeout = Duration.ofSeconds(Math.min(180, request.timeoutSeconds()));
        }
        scopedConfig.set(new ClientConfig(enabled, apiKey, baseUrl, model, timeout));
        configVersion.incrementAndGet();
        return status();
    }

    public Optional<CloudLlmResult> complete(String systemPrompt, String userPrompt, int maxTokens) {
        ClientConfig config = currentConfig();
        if (!config.enabled() || config.apiKey().isBlank()) {
            return Optional.empty();
        }

        try {
            long startedAt = System.nanoTime();
            String payload = objectMapper.writeValueAsString(Map.of(
                    "model", config.model(),
                    "temperature", 0.2,
                    "max_tokens", maxTokens,
                    "messages", List.of(
                            Map.of("role", "system", "content", systemPrompt),
                            Map.of("role", "user", "content", userPrompt)
                    )
            ));
            URI endpoint = URI.create(config.baseUrl() + "/chat/completions");
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(config.timeout())
                    .header("Authorization", "Bearer " + config.apiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("Cloud LLM request failed with status {}", response.statusCode());
                return Optional.empty();
            }

            JsonNode root = objectMapper.readTree(response.body());
            String content = root.path("choices").path(0).path("message").path("content").asText("");
            if (content.isBlank()) {
                return Optional.empty();
            }
            JsonNode usage = root.path("usage");
            long durationMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
            return Optional.of(new CloudLlmResult(
                    content.trim(),
                    config.model(),
                    durationMs,
                    usage.path("prompt_tokens").asInt(0),
                    usage.path("completion_tokens").asInt(0),
                    usage.path("total_tokens").asInt(0)
            ));
        } catch (Exception exception) {
            log.warn("Cloud LLM request failed, falling back to local generator: {}", exception.getMessage());
            return Optional.empty();
        }
    }

    public Optional<CloudEmbeddingResult> embed(String input) {
        ClientConfig config = currentConfig();
        if (!config.enabled() || config.apiKey().isBlank() || input == null || input.isBlank()) {
            return Optional.empty();
        }

        try {
            long startedAt = System.nanoTime();
            String payload = objectMapper.writeValueAsString(Map.of(
                    "model", config.model(),
                    "input", input
            ));
            URI endpoint = URI.create(config.baseUrl() + "/embeddings");
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(config.timeout())
                    .header("Authorization", "Bearer " + config.apiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                lastEmbeddingFailure.set("embedding endpoint returned HTTP " + response.statusCode());
                log.warn("Cloud embedding request failed with status {}", response.statusCode());
                return Optional.empty();
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode embedding = root.path("data").path(0).path("embedding");
            if (!embedding.isArray() || embedding.isEmpty()) {
                lastEmbeddingFailure.set("embedding response did not contain data[0].embedding");
                return Optional.empty();
            }
            List<Double> vector = new ArrayList<>();
            for (JsonNode value : embedding) {
                vector.add(value.asDouble());
            }
            JsonNode usage = root.path("usage");
            long durationMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
            lastEmbeddingFailure.set("");
            return Optional.of(new CloudEmbeddingResult(
                    vector,
                    config.model(),
                    durationMs,
                    usage.path("prompt_tokens").asInt(0),
                    usage.path("total_tokens").asInt(0)
            ));
        } catch (Exception exception) {
            lastEmbeddingFailure.set(exception.getMessage());
            log.warn("Cloud embedding request failed, falling back to local embedding: {}", exception.getMessage());
            return Optional.empty();
        }
    }

    private String normalizedBaseUrl(String baseUrl) {
        String value = baseUrl == null || baseUrl.isBlank() ? "https://api.deepseek.com/v1" : baseUrl.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    public void clearScopedConfig() {
        scopedConfig.remove();
        lastEmbeddingFailure.remove();
    }

    private ClientConfig currentConfig() {
        ClientConfig config = scopedConfig.get();
        return config == null ? defaultConfig : config;
    }

    private String maskedApiKey(String apiKey) {
        if (apiKey.isBlank()) {
            return "";
        }
        int visible = Math.min(4, apiKey.length());
        return "****" + apiKey.substring(apiKey.length() - visible);
    }

    private record ClientConfig(
            boolean enabled,
            String apiKey,
            String baseUrl,
            String model,
            Duration timeout
    ) {
    }
}
