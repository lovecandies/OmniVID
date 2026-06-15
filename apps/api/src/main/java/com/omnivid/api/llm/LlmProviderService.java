package com.omnivid.api.llm;

import com.omnivid.api.common.ApiException;
import com.omnivid.api.auth.CurrentUserService;
import com.omnivid.api.security.ProviderSecretService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class LlmProviderService {
    private final LlmProviderRepository providers;
    private final CloudLlmClient llm;
    private final ProviderSecretService secrets;
    private final CurrentUserService currentUser;

    public LlmProviderService(
            LlmProviderRepository providers,
            CloudLlmClient llm,
            ProviderSecretService secrets,
            CurrentUserService currentUser
    ) {
        this.providers = providers;
        this.llm = llm;
        this.secrets = secrets;
        this.currentUser = currentUser;
    }

    public List<LlmProviderResponse> list() {
        return providers.list(currentUserId()).stream()
                .map(LlmProviderResponse::from)
                .toList();
    }

    public LlmProviderResponse saveAndActivate(LlmProviderSaveRequest request) {
        long userId = currentUserId();
        String apiKey = requireValue(request.apiKey(), "API Key is required");
        String baseUrl = normalizeBaseUrl(defaultValue(request.baseUrl(), "https://api.deepseek.com/v1"));
        String model = defaultValue(request.model(), "deepseek-chat");
        String providerName = defaultValue(request.providerName(), inferProviderName(baseUrl, model));
        int timeoutSeconds = timeoutSeconds(request.timeoutSeconds());

        LlmProviderConfig saved = providers.save(
                userId,
                providerName,
                baseUrl,
                model,
                secrets.encrypt(apiKey),
                mask(apiKey),
                timeoutSeconds
        );
        providers.activate(userId, saved.id());
        LlmProviderConfig active = providers.findById(userId, saved.id()).orElseThrow();
        configureClient(active);
        return LlmProviderResponse.from(active);
    }

    public LlmProviderResponse activate(long id) {
        long userId = currentUserId();
        LlmProviderConfig provider = providers.findById(userId, id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "LLM provider not found"));
        providers.activate(userId, provider.id());
        LlmProviderConfig active = providers.findById(userId, provider.id()).orElseThrow();
        configureClient(active);
        return LlmProviderResponse.from(active);
    }

    public LlmProviderResponse rotateKey(long id, LlmProviderRotateRequest request) {
        long userId = currentUserId();
        LlmProviderConfig provider = providers.findById(userId, id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "LLM provider not found"));
        String apiKey = requireValue(request.apiKey(), "API Key is required");
        providers.updateKey(userId, provider.id(), secrets.encrypt(apiKey), mask(apiKey));
        LlmProviderConfig updated = providers.findById(userId, provider.id()).orElseThrow();
        if (updated.active() && updated.enabled()) {
            configureClient(updated);
        }
        return LlmProviderResponse.from(updated);
    }

    public LlmProviderResponse disable(long id) {
        long userId = currentUserId();
        LlmProviderConfig provider = providers.findById(userId, id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "LLM provider not found"));
        providers.disable(userId, provider.id());
        if (provider.active()) {
            llm.configure(new CloudLlmConfigRequest(false, "", provider.baseUrl(), provider.model(), provider.timeoutSeconds()));
        }
        return LlmProviderResponse.from(providers.findById(userId, provider.id()).orElseThrow());
    }

    public void delete(long id) {
        long userId = currentUserId();
        LlmProviderConfig provider = providers.findById(userId, id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "LLM provider not found"));
        providers.delete(userId, provider.id());
        if (provider.active()) {
            llm.configure(new CloudLlmConfigRequest(false, "", provider.baseUrl(), provider.model(), provider.timeoutSeconds()));
        }
    }

    public void updateTestResult(String status, String message) {
        long userId = currentUserId();
        providers.findActive(userId).ifPresent(provider -> providers.updateTestResult(userId, provider.id(), status, message));
    }

    public void configureActiveForCurrentUser() {
        configureActiveForUserId(currentUserId());
    }

    public void configureActiveForUserId(long userId) {
        providers.findActive(userId).ifPresentOrElse(
                this::configureClient,
                () -> llm.configure(new CloudLlmConfigRequest(false, "", "https://api.deepseek.com/v1", "deepseek-chat", 60))
        );
    }

    private void configureClient(LlmProviderConfig provider) {
        llm.configure(new CloudLlmConfigRequest(
                provider.enabled(),
                secrets.decrypt(provider.apiKeyEncoded()),
                provider.baseUrl(),
                provider.model(),
                provider.timeoutSeconds()
        ));
    }

    private long currentUserId() {
        return currentUser.requireUser().id();
    }

    private String requireValue(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, message);
        }
        return value.trim();
    }

    private String defaultValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private int timeoutSeconds(Integer value) {
        if (value == null || value <= 0) {
            return 60;
        }
        return Math.min(180, Math.max(5, value));
    }

    private String inferProviderName(String baseUrl, String model) {
        String lower = baseUrl.toLowerCase();
        if (lower.contains("deepseek")) {
            return "DeepSeek";
        }
        if (lower.contains("dashscope") || lower.contains("aliyun")) {
            return "Qwen";
        }
        if (lower.contains("openai")) {
            return "OpenAI";
        }
        return model;
    }

    private String normalizeBaseUrl(String baseUrl) {
        String value = baseUrl.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private String mask(String apiKey) {
        int visible = Math.min(4, apiKey.length());
        return "****" + apiKey.substring(apiKey.length() - visible);
    }
}
