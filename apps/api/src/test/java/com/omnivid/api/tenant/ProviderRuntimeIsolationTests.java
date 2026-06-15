package com.omnivid.api.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.omnivid.api.agent.retrieval.AgentRerankService;
import com.omnivid.api.agent.retrieval.OpenAiCompatibleEmbeddingProvider;
import com.omnivid.api.llm.CloudLlmClient;
import com.omnivid.api.llm.CloudLlmConfigRequest;
import com.omnivid.api.llm.CloudLlmConfigResponse;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class ProviderRuntimeIsolationTests {
    @Autowired
    private CloudLlmClient llm;

    @Autowired
    private OpenAiCompatibleEmbeddingProvider embedding;

    @Autowired
    private AgentRerankService rerank;

    @Test
    void concurrentProviderRuntimeConfigurationStaysThreadScoped() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch read = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<RuntimeSnapshot> tenantA = executor.submit(() ->
                    configureAndRead("tenant-a", "https://tenant-a.example/v1", "model-a", ready, read));
            Future<RuntimeSnapshot> tenantB = executor.submit(() ->
                    configureAndRead("tenant-b", "https://tenant-b.example/v1", "model-b", ready, read));

            ready.await();
            read.countDown();

            assertSnapshot(tenantA.get(), "tenant-a", "https://tenant-a.example/v1", "model-a");
            assertSnapshot(tenantB.get(), "tenant-b", "https://tenant-b.example/v1", "model-b");
        }
    }

    private RuntimeSnapshot configureAndRead(
            String tenant,
            String baseUrl,
            String model,
            CountDownLatch ready,
            CountDownLatch read
    ) throws Exception {
        try {
            llm.configure(new CloudLlmConfigRequest(true, "sk-" + tenant, baseUrl, model, 30));
            embedding.configure(true, "qwen", "embed-" + tenant, baseUrl, "embed-" + model, 30);
            rerank.configure(true, "bge", baseUrl, "/rerank", "rerank-" + tenant, "rerank-" + model, 15);
            ready.countDown();
            read.await();
            return new RuntimeSnapshot(llm.status(), embedding.diagnostic(), rerank.diagnostic());
        } finally {
            llm.clearScopedConfig();
            embedding.clearScopedConfig();
            rerank.clearScopedConfig();
        }
    }

    private void assertSnapshot(RuntimeSnapshot snapshot, String tenant, String baseUrl, String model) {
        assertThat(snapshot.llm().baseUrl()).isEqualTo(baseUrl);
        assertThat(snapshot.llm().model()).isEqualTo(model);
        assertThat(snapshot.llm().apiKeyMasked()).endsWith(tenant.substring(tenant.length() - 4));
        assertThat(snapshot.embeddingDiagnostic()).contains(baseUrl).contains("embed-" + model);
        assertThat(snapshot.rerankDiagnostic()).contains(baseUrl).contains("rerank-" + model);
    }

    private record RuntimeSnapshot(
            CloudLlmConfigResponse llm,
            String embeddingDiagnostic,
            String rerankDiagnostic
    ) {
    }
}
