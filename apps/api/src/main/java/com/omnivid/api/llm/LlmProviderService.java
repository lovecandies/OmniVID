package com.omnivid.api.llm;

import com.omnivid.api.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class LlmProviderService {
    private final LlmProviderRepository providers;
    private final CloudLlmClient llm;

    public LlmProviderService(LlmProviderRepository providers, CloudLlmClient llm) {
        this.providers = providers;
        this.llm = llm;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void loadActiveProvider() {
        providers.findActive().ifPresent(this::configureClient);
    }

    public List<LlmProviderResponse> list() {
        return providers.list().stream()
                .map(LlmProviderResponse::from)
                .toList();
    }

    public LlmProviderResponse saveAndActivate(LlmProviderSaveRequest request) {
        String apiKey = requireValue(request.apiKey(), "API Key is required");
        String baseUrl = normalizeBaseUrl(defaultValue(request.baseUrl(), "https://api.deepseek.com/v1"));
        String model = defaultValue(request.model(), "deepseek-chat");
        String providerName = defaultValue(request.providerName(), inferProviderName(baseUrl, model));
        int timeoutSeconds = timeoutSeconds(request.timeoutSeconds());

        LlmProviderConfig saved = providers.save(
                providerName,
                baseUrl,
                model,
                encode(apiKey),
                mask(apiKey),
                timeoutSeconds
        );
        providers.activate(saved.id());
        LlmProviderConfig active = providers.findById(saved.id()).orElseThrow();
        configureClient(active);
        return LlmProviderResponse.from(active);
    }

    public LlmProviderResponse activate(long id) {
        LlmProviderConfig provider = providers.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "LLM provider not found"));
        providers.activate(provider.id());
        LlmProviderConfig active = providers.findById(provider.id()).orElseThrow();
        configureClient(active);
        return LlmProviderResponse.from(active);
    }

    public void updateTestResult(String status, String message) {
        providers.findActive().ifPresent(provider -> providers.updateTestResult(provider.id(), status, message));
    }

    private void configureClient(LlmProviderConfig provider) {
        llm.configure(new CloudLlmConfigRequest(
                provider.enabled(),
                decode(provider.apiKeyEncoded()),
                provider.baseUrl(),
                provider.model(),
                provider.timeoutSeconds()
        ));
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

    private String encode(String apiKey) {
        return Base64.getEncoder().encodeToString(apiKey.getBytes(StandardCharsets.UTF_8));
    }

    private String decode(String encoded) {
        return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
    }

    private String mask(String apiKey) {
        int visible = Math.min(4, apiKey.length());
        return "****" + apiKey.substring(apiKey.length() - visible);
    }
}
