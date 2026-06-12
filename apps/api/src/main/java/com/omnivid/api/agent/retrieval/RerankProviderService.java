package com.omnivid.api.agent.retrieval;

import com.omnivid.api.common.ApiException;
import com.omnivid.api.security.ProviderSecretService;
import com.omnivid.api.transcript.TranscriptSegment;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class RerankProviderService {
    private final RerankProviderRepository providers;
    private final AgentRerankService rerankService;
    private final ProviderSecretService secrets;

    public RerankProviderService(
            RerankProviderRepository providers,
            AgentRerankService rerankService,
            ProviderSecretService secrets
    ) {
        this.providers = providers;
        this.rerankService = rerankService;
        this.secrets = secrets;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void loadActiveProvider() {
        providers.findActive().ifPresent(this::configureProvider);
    }

    public List<RerankProviderResponse> list() {
        return providers.list().stream()
                .map(RerankProviderResponse::from)
                .toList();
    }

    public RerankProviderResponse saveAndActivate(RerankProviderSaveRequest request) {
        String mode = normalizeMode(request.mode());
        String apiKey = request.apiKey() == null ? "" : request.apiKey().trim();
        String baseUrl = normalizeBaseUrl(requireValue(request.baseUrl(), "Rerank Base URL is required"));
        String endpoint = normalizeEndpoint(defaultValue(request.endpoint(), "/rerank"));
        String model = defaultValue(request.model(), "bge-reranker-v2-m3");
        String providerName = defaultValue(request.providerName(), defaultProviderName(mode, model));
        int timeoutSeconds = timeoutSeconds(request.timeoutSeconds());

        RerankProviderConfig saved = providers.save(
                providerName,
                mode,
                baseUrl,
                endpoint,
                model,
                secrets.encrypt(apiKey),
                mask(apiKey),
                timeoutSeconds
        );
        providers.activate(saved.id());
        RerankProviderConfig active = providers.findById(saved.id()).orElseThrow();
        configureProvider(active);
        return RerankProviderResponse.from(active);
    }

    public RerankProviderResponse activate(long id) {
        RerankProviderConfig provider = providers.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Rerank provider not found"));
        providers.activate(provider.id());
        RerankProviderConfig active = providers.findById(provider.id()).orElseThrow();
        configureProvider(active);
        return RerankProviderResponse.from(active);
    }

    public RerankProviderResponse rotateKey(long id, RerankProviderRotateRequest request) {
        RerankProviderConfig provider = providers.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Rerank provider not found"));
        String apiKey = request.apiKey() == null ? "" : request.apiKey().trim();
        providers.updateKey(provider.id(), secrets.encrypt(apiKey), mask(apiKey));
        RerankProviderConfig updated = providers.findById(provider.id()).orElseThrow();
        if (updated.active() && updated.enabled()) {
            configureProvider(updated);
        }
        return RerankProviderResponse.from(updated);
    }

    public RerankProviderResponse disable(long id) {
        RerankProviderConfig provider = providers.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Rerank provider not found"));
        providers.disable(provider.id());
        if (provider.active()) {
            rerankService.configure(true, "local", "", "/rerank", "", "", provider.timeoutSeconds());
        }
        return RerankProviderResponse.from(providers.findById(provider.id()).orElseThrow());
    }

    public void delete(long id) {
        RerankProviderConfig provider = providers.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Rerank provider not found"));
        providers.delete(provider.id());
        if (provider.active()) {
            rerankService.configure(true, "local", "", "/rerank", "", "", provider.timeoutSeconds());
        }
    }

    public RerankProviderTestResponse testActive() {
        RerankProviderConfig active = providers.findActive()
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "No active rerank provider"));
        configureProvider(active);
        List<AgentRerankService.RerankInput> inputs = List.of(
                new AgentRerankService.RerankInput(
                        new TranscriptSegment(1, 1, 0, 0, 1000, "System", "OmniVid uses Java, MySQL, Redis and RAG.", 12),
                        0.7,
                        3,
                        3
                ),
                new AgentRerankService.RerankInput(
                        new TranscriptSegment(2, 1, 1, 1000, 2000, "System", "Unrelated weather content.", 4),
                        0.1,
                        0,
                        0
                )
        );
        rerankService.rerank("Java Redis RAG", inputs, 2);
        boolean success = rerankService.remoteActive();
        String message = rerankService.diagnostic();
        providers.updateTestResult(active.id(), success ? "OK" : "FAILED", message);
        return new RerankProviderTestResponse(success, message, rerankService.providerName(), active.model());
    }

    private void configureProvider(RerankProviderConfig provider) {
        rerankService.configure(
                provider.enabled(),
                provider.mode(),
                provider.baseUrl(),
                provider.endpoint(),
                secrets.decrypt(provider.apiKeyEncoded()),
                provider.model(),
                provider.timeoutSeconds()
        );
    }

    private String normalizeMode(String value) {
        String mode = value == null ? "bge" : value.trim().toLowerCase(Locale.ROOT);
        if (List.of("bge", "openai-compatible").contains(mode)) {
            return mode;
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, "Rerank mode must be bge or openai-compatible");
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

    private String normalizeBaseUrl(String baseUrl) {
        String value = baseUrl.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private String normalizeEndpoint(String value) {
        String endpoint = value == null || value.isBlank() ? "/rerank" : value.trim();
        return endpoint.startsWith("/") ? endpoint : "/" + endpoint;
    }

    private int timeoutSeconds(Integer value) {
        if (value == null || value <= 0) {
            return 15;
        }
        return Math.min(120, Math.max(3, value));
    }

    private String defaultProviderName(String mode, String model) {
        return ("bge".equals(mode) ? "BGE Rerank" : "Rerank") + " / " + model;
    }

    private String mask(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return "";
        }
        int visible = Math.min(4, apiKey.length());
        return "****" + apiKey.substring(apiKey.length() - visible);
    }
}
