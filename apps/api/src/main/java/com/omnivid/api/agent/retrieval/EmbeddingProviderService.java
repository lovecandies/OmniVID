package com.omnivid.api.agent.retrieval;

import com.omnivid.api.common.ApiException;
import com.omnivid.api.auth.CurrentUserService;
import com.omnivid.api.security.ProviderSecretService;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class EmbeddingProviderService {
    private final EmbeddingProviderRepository providers;
    private final OpenAiCompatibleEmbeddingProvider embeddingProvider;
    private final ProviderSecretService secrets;
    private final CurrentUserService currentUser;

    public EmbeddingProviderService(
            EmbeddingProviderRepository providers,
            OpenAiCompatibleEmbeddingProvider embeddingProvider,
            ProviderSecretService secrets,
            CurrentUserService currentUser
    ) {
        this.providers = providers;
        this.embeddingProvider = embeddingProvider;
        this.secrets = secrets;
        this.currentUser = currentUser;
    }

    public List<EmbeddingProviderResponse> list() {
        return providers.list(currentUserId()).stream()
                .map(EmbeddingProviderResponse::from)
                .toList();
    }

    public EmbeddingProviderResponse saveAndActivate(EmbeddingProviderSaveRequest request) {
        long userId = currentUserId();
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
                userId,
                providerName,
                mode,
                baseUrl,
                model,
                secrets.encrypt(apiKey),
                mask(apiKey),
                timeoutSeconds
        );
        providers.activate(userId, saved.id());
        EmbeddingProviderConfig active = providers.findById(userId, saved.id()).orElseThrow();
        configureProvider(active);
        return EmbeddingProviderResponse.from(active);
    }

    public EmbeddingProviderResponse activate(long id) {
        long userId = currentUserId();
        EmbeddingProviderConfig provider = providers.findById(userId, id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Embedding provider not found"));
        providers.activate(userId, provider.id());
        EmbeddingProviderConfig active = providers.findById(userId, provider.id()).orElseThrow();
        configureProvider(active);
        return EmbeddingProviderResponse.from(active);
    }

    public EmbeddingProviderResponse rotateKey(long id, EmbeddingProviderRotateRequest request) {
        long userId = currentUserId();
        EmbeddingProviderConfig provider = providers.findById(userId, id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Embedding provider not found"));
        String apiKey = request.apiKey() == null ? "" : request.apiKey().trim();
        if (!"bge".equals(provider.mode()) && apiKey.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Embedding API Key is required for " + provider.mode());
        }
        providers.updateKey(userId, provider.id(), secrets.encrypt(apiKey), mask(apiKey));
        EmbeddingProviderConfig updated = providers.findById(userId, provider.id()).orElseThrow();
        if (updated.active() && updated.enabled()) {
            configureProvider(updated);
        }
        return EmbeddingProviderResponse.from(updated);
    }

    public EmbeddingProviderResponse disable(long id) {
        long userId = currentUserId();
        EmbeddingProviderConfig provider = providers.findById(userId, id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Embedding provider not found"));
        providers.disable(userId, provider.id());
        if (provider.active()) {
            embeddingProvider.configure(false, "local", "", "", "", provider.timeoutSeconds());
        }
        return EmbeddingProviderResponse.from(providers.findById(userId, provider.id()).orElseThrow());
    }

    public void delete(long id) {
        long userId = currentUserId();
        EmbeddingProviderConfig provider = providers.findById(userId, id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Embedding provider not found"));
        providers.delete(userId, provider.id());
        if (provider.active()) {
            embeddingProvider.configure(false, "local", "", "", "", provider.timeoutSeconds());
        }
    }

    public EmbeddingProviderTestResponse testActive() {
        long userId = currentUserId();
        EmbeddingProviderConfig active = providers.findActive(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "No active embedding provider"));
        configureProvider(active);
        embeddingProvider.embed("OmniVid embedding connection test: Java, MySQL, Redis, RAG.");
        boolean success = embeddingProvider.cloudBacked();
        String message = success ? embeddingProvider.diagnostic() : embeddingProvider.diagnostic();
        providers.updateTestResult(userId, active.id(), success ? "OK" : "FAILED", message);
        return new EmbeddingProviderTestResponse(
                success,
                message,
                embeddingProvider.providerName(),
                active.model(),
                embeddingProvider.dimensions()
        );
    }

    public void configureActiveForCurrentUser() {
        providers.findActive(currentUserId()).ifPresentOrElse(
                this::configureProvider,
                () -> embeddingProvider.configure(false, "local", "", "", "", 30)
        );
    }

    private void configureProvider(EmbeddingProviderConfig provider) {
        embeddingProvider.configure(
                provider.enabled(),
                provider.mode(),
                secrets.decrypt(provider.apiKeyEncoded()),
                provider.baseUrl(),
                provider.model(),
                provider.timeoutSeconds()
        );
    }

    private long currentUserId() {
        return currentUser.requireUser().id();
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

    private String mask(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return "";
        }
        int visible = Math.min(4, apiKey.length());
        return "****" + apiKey.substring(apiKey.length() - visible);
    }
}
