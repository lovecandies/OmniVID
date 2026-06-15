package com.omnivid.api.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class TenantIsolationTests {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void videosKnowledgeBasesAndProvidersAreScopedToCurrentUser() throws Exception {
        MockHttpSession userA = register("tenant-a");
        MockHttpSession userB = register("tenant-b");
        String md5 = "%032x".formatted(System.nanoTime());

        JsonNode uploadA = upload(userA, "tenant-a-video.mp4", md5);
        JsonNode uploadB = upload(userB, "tenant-b-video.mp4", md5);
        long videoA = uploadA.path("video").path("id").asLong();
        long videoB = uploadB.path("video").path("id").asLong();
        long jobA = uploadA.path("job").path("id").asLong();

        assertThat(videoB).isNotEqualTo(videoA);
        assertThat(listContains(getJson(userA, "/api/videos"), videoA)).isTrue();
        assertThat(listContains(getJson(userB, "/api/videos"), videoA)).isFalse();

        mockMvc.perform(get("/api/videos/{videoId}", videoA).session(userB))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/videos/{videoId}/media", videoA).session(userB))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/videos/{videoId}/transcripts", videoA).session(userB))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/videos/{videoId}/transcripts/search?q=redis", videoA).session(userB))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/videos/{videoId}/transcripts/versions", videoA).session(userB))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/videos/{videoId}/transcripts/versions/1", videoA).session(userB))
                .andExpect(status().isNotFound());
        mockMvc.perform(patch("/api/videos/{videoId}/transcripts/1", videoA).with(csrf())
                        .session(userB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"cross tenant edit\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/videos/{videoId}/transcripts/versions/1/restore", videoA).with(csrf())
                        .session(userB))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/videos/{videoId}/summaries", videoA).session(userB))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/videos/{videoId}/progress", videoA).session(userB))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/videos/{videoId}/progress/stream", videoA).session(userB))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/videos/{videoId}/retry", videoA).with(csrf()).session(userB))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/videos/{videoId}/asr/diagnostics", videoA).session(userB))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/videos/{videoId}/asr/evaluate-ocr", videoA).session(userB))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/videos/{videoId}/asr/repair-encoding", videoA).with(csrf()).session(userB))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/videos/{videoId}/asr/reprocess", videoA).with(csrf()).session(userB))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/videos/{videoId}/asr/fuse-ocr", videoA).with(csrf()).session(userB))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/videos/{videoId}/asr/align-ocr", videoA).with(csrf()).session(userB))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/videos/{videoId}/asr/refine-low-confidence", videoA).with(csrf()).session(userB))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/jobs/{jobId}", jobA).session(userB))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/videos/{videoId}/agent/messages", videoA).session(userB))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/videos/{videoId}/agent/context", videoA).session(userB))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/videos/{videoId}/agent/ask", videoA).with(csrf())
                        .session(userB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"What is this video about?\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/videos/{videoId}/agent/messages", videoA).with(csrf()).session(userB))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/videos/{videoId}/exports", videoA).with(csrf())
                        .session(userB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"summaryType\":\"CORE_POINTS\",\"format\":\"MARKDOWN\"}"))
                .andExpect(status().isNotFound());

        long knowledgeBaseId = createKnowledgeBase(userA);
        mockMvc.perform(post("/api/knowledge-bases/{id}/videos", knowledgeBaseId).with(csrf())
                        .session(userA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "videoId": %d
                                }
                                """.formatted(videoA)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/knowledge-bases/{id}", knowledgeBaseId).session(userB))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/knowledge-bases/{id}/coverage", knowledgeBaseId).session(userB))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/knowledge-bases/{id}/compare", knowledgeBaseId).with(csrf())
                        .session(userB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"Compare the videos\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/knowledge-bases/{id}/agent/ask", knowledgeBaseId).with(csrf())
                        .session(userB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"Compare the videos\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/knowledge-bases/{id}/videos", knowledgeBaseId).with(csrf())
                        .session(userB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "videoId": %d
                                }
                                """.formatted(videoB)))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/knowledge-bases/{id}/videos/{videoId}", knowledgeBaseId, videoA).with(csrf()).session(userB))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/knowledge-bases/{id}", knowledgeBaseId).with(csrf()).session(userB))
                .andExpect(status().isNotFound());

        long providerId = saveLlmProvider(userA);
        assertThat(listContains(getJson(userA, "/api/llm/providers"), providerId)).isTrue();
        assertThat(listContains(getJson(userB, "/api/llm/providers"), providerId)).isFalse();

        mockMvc.perform(post("/api/llm/providers/{id}/activate", providerId).with(csrf()).session(userB))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/llm/providers/{id}/rotate", providerId).with(csrf())
                        .session(userB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiKey\":\"sk-user-b-should-not-work\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/llm/providers/{id}/disable", providerId).with(csrf()).session(userB))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/llm/providers/{id}", providerId).with(csrf()).session(userB))
                .andExpect(status().isNotFound());

        long embeddingProviderId = saveEmbeddingProvider(userA);
        assertThat(listContains(getJson(userA, "/api/embedding/providers"), embeddingProviderId)).isTrue();
        assertThat(listContains(getJson(userB, "/api/embedding/providers"), embeddingProviderId)).isFalse();
        mockMvc.perform(post("/api/embedding/providers/{id}/activate", embeddingProviderId).with(csrf()).session(userB))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/embedding/providers/{id}/rotate", embeddingProviderId).with(csrf())
                        .session(userB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiKey\":\"sk-user-b-should-not-work\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/embedding/providers/{id}/disable", embeddingProviderId).with(csrf()).session(userB))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/embedding/providers/{id}", embeddingProviderId).with(csrf()).session(userB))
                .andExpect(status().isNotFound());

        long rerankProviderId = saveRerankProvider(userA);
        assertThat(listContains(getJson(userA, "/api/rerank/providers"), rerankProviderId)).isTrue();
        assertThat(listContains(getJson(userB, "/api/rerank/providers"), rerankProviderId)).isFalse();
        mockMvc.perform(post("/api/rerank/providers/{id}/activate", rerankProviderId).with(csrf()).session(userB))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/rerank/providers/{id}/rotate", rerankProviderId).with(csrf())
                        .session(userB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiKey\":\"sk-user-b-should-not-work\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/rerank/providers/{id}/disable", rerankProviderId).with(csrf()).session(userB))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/rerank/providers/{id}", rerankProviderId).with(csrf()).session(userB))
                .andExpect(status().isNotFound());

        String chunkSessionId = createChunkUploadSession(userA);
        mockMvc.perform(get("/api/videos/upload/chunked/sessions/{sessionId}", chunkSessionId).session(userB))
                .andExpect(status().isNotFound());
    }

    private MockHttpSession register(String prefix) throws Exception {
        String email = prefix + "-" + System.nanoTime() + "@example.com";
        MvcResult result = mockMvc.perform(post("/api/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "%s",
                                  "password": "password123",
                                  "nickname": "%s"
                                }
                                """.formatted(email, prefix)))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private JsonNode upload(MockHttpSession session, String originalName, String md5) throws Exception {
        return objectMapper.readTree(mockMvc.perform(post("/api/videos/upload/complete").with(csrf())
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "originalName": "%s",
                                  "md5": "%s",
                                  "durationMs": 120000
                                }
                                """.formatted(originalName, md5)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    }

    private long createKnowledgeBase(MockHttpSession session) throws Exception {
        JsonNode root = objectMapper.readTree(mockMvc.perform(post("/api/knowledge-bases").with(csrf())
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Tenant KB %d",
                                  "description": "tenant isolation test"
                                }
                                """.formatted(System.nanoTime())))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        return root.path("id").asLong();
    }

    private long saveLlmProvider(MockHttpSession session) throws Exception {
        JsonNode root = objectMapper.readTree(mockMvc.perform(post("/api/llm/providers").with(csrf())
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "providerName": "DeepSeek Test",
                                  "apiKey": "sk-test-tenant-isolation",
                                  "baseUrl": "https://api.deepseek.com/v1",
                                  "model": "deepseek-chat",
                                  "timeoutSeconds": 30
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        return root.path("id").asLong();
    }

    private long saveEmbeddingProvider(MockHttpSession session) throws Exception {
        JsonNode root = objectMapper.readTree(mockMvc.perform(post("/api/embedding/providers").with(csrf())
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "providerName": "BGE Tenant Test",
                                  "mode": "bge",
                                  "baseUrl": "http://localhost:8000/v1",
                                  "model": "BAAI/bge-m3",
                                  "apiKey": "",
                                  "timeoutSeconds": 30
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        return root.path("id").asLong();
    }

    private long saveRerankProvider(MockHttpSession session) throws Exception {
        JsonNode root = objectMapper.readTree(mockMvc.perform(post("/api/rerank/providers").with(csrf())
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "providerName": "BGE Rerank Tenant Test",
                                  "mode": "bge",
                                  "baseUrl": "http://localhost:8000",
                                  "endpoint": "/rerank",
                                  "model": "bge-reranker-v2-m3",
                                  "apiKey": "",
                                  "timeoutSeconds": 15
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        return root.path("id").asLong();
    }

    private String createChunkUploadSession(MockHttpSession session) throws Exception {
        String md5 = "%032x".formatted(System.nanoTime());
        JsonNode root = objectMapper.readTree(mockMvc.perform(post("/api/videos/upload/chunked/sessions").with(csrf())
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fileName": "tenant-chunk.mp4",
                                  "fileSize": 262144,
                                  "fileMd5": "%s",
                                  "partSize": 262144,
                                  "totalParts": 1
                                }
                                """.formatted(md5)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        return root.path("sessionId").asText();
    }

    private JsonNode getJson(MockHttpSession session, String path) throws Exception {
        return objectMapper.readTree(mockMvc.perform(get(path).session(session))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    }

    private boolean listContains(JsonNode list, long id) {
        for (JsonNode item : list) {
            if (item.path("id").asLong() == id) {
                return true;
            }
        }
        return false;
    }
}
