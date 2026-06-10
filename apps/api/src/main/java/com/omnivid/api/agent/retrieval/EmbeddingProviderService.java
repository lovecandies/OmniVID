package com.omnivid.api.agent.retrieval;

import com.omnivid.api.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class EmbeddingProviderService {
    private final EmbeddingProviderRepository providers;
    private final OpenAiCompatibleEmbeddingProvider embeddingProvider;

    public EmbeddingProviderService(
            EmbeddingProviderRepository providers,
            OpenAiCompatibleEmbeddingProvider embeddingProvider
    ) {
        this.providers = providers;
        this.embeddingProvider = embeddingProvider;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void loadActiveProvider() {
        providers.findActive().ifPresent(this::configureProvider);
    }

    public List<EmbeddingProviderResponse> list() {
        return providers.list().stream()
                .map(EmbeddingProviderResponse::from)
                .toList();
    }

    public EmbeddingProviderResponse saveAndActivate(EmbeddingProviderSaveRequest request) {
        String mode = normalizeMode(request.mode());
        String apiKey = request.apiKey() == null ? "" : request.apiKey().trim();
        if (!"bge".equals(mode) && apiKey.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Embedding API Key is required for " + mode);
        }
        String baseUrl = normalizeBaseUrl(defaultBaseUrl(request.baseUrl(), mode));
        String model = defaultModel(request.model(), mode);
        String providerName = defaultProviderName(request.providerName(), mode, model);
        int timeoutSeconds = timeoutSeconds(request.timeoutSeconds());

        EmbeddingProviderConfig saved = providers.save(
                providerName,
                mode,
                baseUrl,
                model,
                encode(apiKey),
                mask(apiKey),
                timeoutSeconds
        );
        providers.activate(saved.id());
        EmbeddingProviderConfig active = providers.findById(saved.id()).orElseThrow();
        configureProvider(active);
        return EmbeddingProviderResponse.from(active);
    }

    public EmbeddingProviderResponse activate(long id) {
        EmbeddingProviderConfig provider = providers.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Embedding provider not found"));
        providers.activate(provider.id());
        EmbeddingProviderConfig active = providers.findById(provider.id()).orElseThrow();
        configureProvider(active);
        return EmbeddingProviderResponse.from(active);
    }

    public EmbeddingProviderTestResponse testActive() {
        EmbeddingProviderConfig active = providers.findActive()
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "No active embedding provider"));
        configureProvider(active);
        embeddingProvider.embed("OmniVid embedding connection test: Java, MySQL, Redis, RAG.");
        boolean success = embeddingProvider.cloudBacked();
        String message = success ? embeddingProvider.diagnostic() : embeddingProvider.diagnostic();
        providers.updateTestResult(active.id(), success ? "OK" : "FAILED", message);
        return new EmbeddingProviderTestResponse(
                success,
                message,
                embeddingProvider.providerName(),
                active.model(),
                embeddingProvider.dimensions()
        );
    }

    private void configureProvider(EmbeddingProviderConfig provider) {
        embeddingProvider.configure(
                provider.enabled(),
                provider.mode(),
                decode(provider.apiKeyEncoded()),
                provider.baseUrl(),
                provider.model(),
                provider.timeoutSeconds()
        );
    }

    private String normalizeMode(String value) {
        String mode = value == null ? "qwen" : value.trim().toLowerCase(Locale.ROOT);
        if (List.of("qwen", "openai", "bge").contains(mode)) {
            return mode;
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, "Embedding mode must be qwen, openai or bge");
    }

    private String defaultBaseUrl(String value, String mode) {
        if (value != null && !value.isBlank()) {
            return value.trim();
        }
        return switch (mode) {
            case "openai" -> "https://api.openai.com/v1";
            case "bge" -> "http://localhost:8000/v1";
            default -> "https://dashscope.aliyuncs.com/compatible-mode/v1";
        };
    }

    private String defaultModel(String value, String mode) {
        if (value != null && !value.isBlank()) {
            return value.trim();
        }
        return switch (mode) {
            case "openai" -> "text-embedding-3-small";
            case "bge" -> "BAAI/bge-m3";
            default -> "text-embedding-v4";
        };
    }

    private String defaultProviderName(String value, String mode, String model) {
        if (value != null && !value.isBlank()) {
            return value.trim();
        }
        return switch (mode) {
            case "openai" -> "OpenAI Embedding";
            case "bge" -> "BGE Embedding";
            default -> "Qwen Embedding";
        } + " / " + model;
    }

    private String normalizeBaseUrl(String baseUrl) {
        String value = baseUrl.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private int timeoutSeconds(Integer value) {
        if (value == null || value <= 0) {
            return 30;
        }
        return Math.min(120, Math.max(5, value));
    }

    private String encode(String apiKey) {
        return Base64.getEncoder().encodeToString(apiKey.getBytes(StandardCharsets.UTF_8));
    }

    private String decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return "";
        }
        return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
    }

    private String mask(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return "";
        }
        int visible = Math.min(4, apiKey.length());
        return "****" + apiKey.substring(apiKey.length() - visible);
    }
}
