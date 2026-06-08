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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class CloudLlmClient {
    private static final Logger log = LoggerFactory.getLogger(CloudLlmClient.class);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private volatile boolean enabled;
    private volatile String apiKey;
    private volatile String baseUrl;
    private volatile String model;
    private volatile Duration timeout;
    private volatile int configVersion;
    private volatile String lastEmbeddingFailure = "";

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
        this.enabled = enabled;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.baseUrl = normalizedBaseUrl(baseUrl);
        this.model = model;
        this.timeout = timeout;
    }

    public boolean available() {
        return enabled && !apiKey.isBlank();
    }

    public String model() {
        return model;
    }

    public int configVersion() {
        return configVersion;
    }

    public String lastEmbeddingFailure() {
        return lastEmbeddingFailure;
    }

    public CloudLlmConfigResponse status() {
        return new CloudLlmConfigResponse(
                enabled,
                !apiKey.isBlank(),
                baseUrl,
                model,
                Math.toIntExact(timeout.toSeconds()),
                maskedApiKey()
        );
    }

    public CloudLlmConfigResponse configure(CloudLlmConfigRequest request) {
        enabled = request.enabled();
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
        configVersion++;
        return status();
    }

    public Optional<CloudLlmResult> complete(String systemPrompt, String userPrompt, int maxTokens) {
        boolean currentEnabled = enabled;
        String currentApiKey = apiKey;
        String currentBaseUrl = baseUrl;
        String currentModel = model;
        Duration currentTimeout = timeout;
        if (!currentEnabled || currentApiKey.isBlank()) {
            return Optional.empty();
        }

        try {
            long startedAt = System.nanoTime();
            String payload = objectMapper.writeValueAsString(Map.of(
                    "model", currentModel,
                    "temperature", 0.2,
                    "max_tokens", maxTokens,
                    "messages", List.of(
                            Map.of("role", "system", "content", systemPrompt),
                            Map.of("role", "user", "content", userPrompt)
                    )
            ));
            URI endpoint = URI.create(currentBaseUrl + "/chat/completions");
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(currentTimeout)
                    .header("Authorization", "Bearer " + currentApiKey)
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
                    currentModel,
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
        boolean currentEnabled = enabled;
        String currentApiKey = apiKey;
        String currentBaseUrl = baseUrl;
        String currentModel = model;
        Duration currentTimeout = timeout;
        if (!currentEnabled || currentApiKey.isBlank() || input == null || input.isBlank()) {
            return Optional.empty();
        }

        try {
            long startedAt = System.nanoTime();
            String payload = objectMapper.writeValueAsString(Map.of(
                    "model", currentModel,
                    "input", input
            ));
            URI endpoint = URI.create(currentBaseUrl + "/embeddings");
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(currentTimeout)
                    .header("Authorization", "Bearer " + currentApiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                lastEmbeddingFailure = "embedding endpoint returned HTTP " + response.statusCode();
                log.warn("Cloud embedding request failed with status {}", response.statusCode());
                return Optional.empty();
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode embedding = root.path("data").path(0).path("embedding");
            if (!embedding.isArray() || embedding.isEmpty()) {
                lastEmbeddingFailure = "embedding response did not contain data[0].embedding";
                return Optional.empty();
            }
            List<Double> vector = new ArrayList<>();
            for (JsonNode value : embedding) {
                vector.add(value.asDouble());
            }
            JsonNode usage = root.path("usage");
            long durationMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
            lastEmbeddingFailure = "";
            return Optional.of(new CloudEmbeddingResult(
                    vector,
                    currentModel,
                    durationMs,
                    usage.path("prompt_tokens").asInt(0),
                    usage.path("total_tokens").asInt(0)
            ));
        } catch (Exception exception) {
            lastEmbeddingFailure = exception.getMessage();
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

    private String maskedApiKey() {
        if (apiKey.isBlank()) {
            return "";
        }
        int visible = Math.min(4, apiKey.length());
        return "****" + apiKey.substring(apiKey.length() - visible);
    }
}
