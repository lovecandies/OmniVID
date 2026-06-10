package com.omnivid.api.runtime;

import com.omnivid.api.agent.retrieval.AgentRerankService;
import com.omnivid.api.agent.retrieval.TranscriptVectorSearch;
import com.omnivid.api.llm.CloudLlmClient;
import com.omnivid.api.llm.CloudLlmConfigResponse;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/runtime")
public class RuntimeStatusController {
    private final Environment environment;
    private final DataSource dataSource;
    private final ObjectProvider<StringRedisTemplate> redis;
    private final CloudLlmClient llm;
    private final TranscriptVectorSearch vectorSearch;
    private final AgentRerankService rerankService;
    private final String dedupeLockMode;
    private final String progressCacheMode;
    private final String rateLimitMode;
    private final String answerCacheMode;
    private final String shortTermMemoryMode;

    public RuntimeStatusController(
            Environment environment,
            DataSource dataSource,
            ObjectProvider<StringRedisTemplate> redis,
            CloudLlmClient llm,
            TranscriptVectorSearch vectorSearch,
            AgentRerankService rerankService,
            @Value("${omnivid.dedupe-lock.mode:local}") String dedupeLockMode,
            @Value("${omnivid.progress-cache.mode:local}") String progressCacheMode,
            @Value("${omnivid.agent-rate-limit.mode:local}") String rateLimitMode,
            @Value("${omnivid.agent-answer-cache.mode:local}") String answerCacheMode,
            @Value("${omnivid.agent-short-term-memory.mode:local}") String shortTermMemoryMode
    ) {
        this.environment = environment;
        this.dataSource = dataSource;
        this.redis = redis;
        this.llm = llm;
        this.vectorSearch = vectorSearch;
        this.rerankService = rerankService;
        this.dedupeLockMode = dedupeLockMode;
        this.progressCacheMode = progressCacheMode;
        this.rateLimitMode = rateLimitMode;
        this.answerCacheMode = answerCacheMode;
        this.shortTermMemoryMode = shortTermMemoryMode;
    }

    @GetMapping("/status")
    RuntimeStatusResponse status() {
        CloudLlmConfigResponse llmStatus = llm.status();
        return new RuntimeStatusResponse(
                profile(),
                databaseStatus(),
                redisStatus(),
                new RuntimeStatusResponse.RuntimeLlmStatus(
                        llmStatus.enabled(),
                        llmStatus.configured(),
                        llmStatus.baseUrl(),
                        llmStatus.model(),
                        vectorSearch.providerName(),
                        vectorSearch.embeddingDiagnostic(),
                        vectorSearch.indexName(),
                        vectorSearch.dimensions(),
                        vectorSearch.vectorStoreMode(),
                        vectorSearch.vectorStoreConnected(),
                        vectorSearch.vectorStoreEndpoint(),
                        rerankService.providerName(),
                        rerankService.diagnostic()
                )
        );
    }

    private RuntimeStatusResponse.RuntimeDatabaseStatus databaseStatus() {
        try (Connection connection = dataSource.getConnection()) {
            return new RuntimeStatusResponse.RuntimeDatabaseStatus(
                    true,
                    connection.getMetaData().getDatabaseProductName(),
                    connection.getMetaData().getURL(),
                    "video_md5_unique + job_version + transcript_index"
            );
        } catch (SQLException exception) {
            return new RuntimeStatusResponse.RuntimeDatabaseStatus(false, "unknown", "", "connection_failed");
        }
    }

    private RuntimeStatusResponse.RuntimeRedisStatus redisStatus() {
        boolean connected = false;
        StringRedisTemplate template = redis.getIfAvailable();
        if (template != null && template.getConnectionFactory() != null) {
            try (RedisConnection connection = template.getConnectionFactory().getConnection()) {
                connected = "PONG".equalsIgnoreCase(connection.ping());
            } catch (Exception ignored) {
                connected = false;
            }
        }
        return new RuntimeStatusResponse.RuntimeRedisStatus(
                connected,
                dedupeLockMode,
                progressCacheMode,
                rateLimitMode,
                answerCacheMode,
                shortTermMemoryMode
        );
    }

    private String profile() {
        String[] profiles = environment.getActiveProfiles();
        return profiles.length == 0 ? "default" : String.join(",", profiles);
    }
}
