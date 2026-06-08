package com.omnivid.api.agent.retrieval;

import java.util.Map;

public interface EmbeddingProvider {
    String providerName();

    int dimensions();

    Map<Integer, Double> embed(String text);

    default boolean cloudBacked() {
        return false;
    }

    default String diagnostic() {
        return providerName();
    }
}
