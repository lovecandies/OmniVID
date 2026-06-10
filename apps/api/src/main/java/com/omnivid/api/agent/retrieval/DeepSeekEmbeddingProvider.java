package com.omnivid.api.agent.retrieval;

import com.omnivid.api.llm.CloudEmbeddingResult;
import com.omnivid.api.llm.CloudLlmClient;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class DeepSeekEmbeddingProvider implements EmbeddingProvider {
    private final CloudLlmClient llm;
    private final LocalHashEmbeddingProvider fallback;
    private volatile int failedConfigVersion = -1;
    private volatile boolean lastCloudSuccess;
    private volatile int cloudDimensions;

    public DeepSeekEmbeddingProvider(CloudLlmClient llm, LocalHashEmbeddingProvider fallback) {
        this.llm = llm;
        this.fallback = fallback;
        this.cloudDimensions = fallback.dimensions();
    }

    @Override
    public String providerName() {
        if (lastCloudSuccess) {
            return "deepseek-embedding";
        }
        if (llm.available() && failedConfigVersion == llm.configVersion()) {
            return "local-hash-fallback";
        }
        return fallback.providerName();
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

        int currentVersion = llm.configVersion();
        if (llm.available() && failedConfigVersion != currentVersion) {
            Optional<CloudEmbeddingResult> cloud = llm.embed(text);
            if (cloud.isPresent() && !cloud.get().vector().isEmpty()) {
                lastCloudSuccess = true;
                cloudDimensions = cloud.get().vector().size();
                failedConfigVersion = -1;
                return denseToSparse(cloud.get().vector());
            }
            lastCloudSuccess = false;
            failedConfigVersion = currentVersion;
        }

        lastCloudSuccess = false;
        return fallback.embed(text);
    }

    @Override
    public boolean cloudBacked() {
        return lastCloudSuccess;
    }

    @Override
    public String diagnostic() {
        if (lastCloudSuccess) {
            return "DeepSeek embedding is active";
        }
        if (!llm.available()) {
            return "Cloud embedding disabled because DeepSeek API key is not enabled";
        }
        String failure = llm.lastEmbeddingFailure();
        if (failure == null || failure.isBlank()) {
            return "Cloud embedding not proven yet; using local hash embedding";
        }
        return "DeepSeek embedding unavailable: " + failure + "; using local hash embedding";
    }

    private Map<Integer, Double> denseToSparse(List<Double> vector) {
        Map<Integer, Double> sparse = new HashMap<>();
        for (int index = 0; index < vector.size(); index++) {
            double value = vector.get(index);
            if (value != 0) {
                sparse.put(index, value);
            }
        }
        return sparse;
    }
}
