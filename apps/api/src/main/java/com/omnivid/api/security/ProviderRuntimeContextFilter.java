package com.omnivid.api.security;

import com.omnivid.api.agent.retrieval.AgentRerankService;
import com.omnivid.api.agent.retrieval.OpenAiCompatibleEmbeddingProvider;
import com.omnivid.api.llm.CloudLlmClient;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class ProviderRuntimeContextFilter extends OncePerRequestFilter {
    private final CloudLlmClient llm;
    private final OpenAiCompatibleEmbeddingProvider embedding;
    private final AgentRerankService rerank;

    public ProviderRuntimeContextFilter(
            CloudLlmClient llm,
            OpenAiCompatibleEmbeddingProvider embedding,
            AgentRerankService rerank
    ) {
        this.llm = llm;
        this.embedding = embedding;
        this.rerank = rerank;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        try {
            filterChain.doFilter(request, response);
        } finally {
            llm.clearScopedConfig();
            embedding.clearScopedConfig();
            rerank.clearScopedConfig();
        }
    }
}
