package com.omnivid.api.admin;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

@SpringBootTest(properties = {
        "omnivid.security.admin-emails=admin-v22@example.com"
})
@AutoConfigureMockMvc
class AdminConsoleTests {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void adminCanInspectUsersAndTasksWithoutSeeingProviderSecrets() throws Exception {
        MockHttpSession userSession = register("admin-user-" + System.nanoTime() + "@example.com", "User");
        MockHttpSession adminSession = register("admin-v22@example.com", "Admin");
        JsonNode upload = upload(userSession);
        long userId = upload.path("video").path("userId").asLong();
        long jobId = upload.path("job").path("id").asLong();

        mockMvc.perform(post("/api/llm/providers").with(csrf())
                        .session(userSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "providerName": "DeepSeek",
                                  "apiKey": "sk-admin-secret-raw",
                                  "baseUrl": "https://api.deepseek.com/v1",
                                  "model": "deepseek-chat",
                                  "timeoutSeconds": 60
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apiKeyMasked", containsString("raw")));

        mockMvc.perform(get("/api/admin/users").session(userSession))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/vector-index/rebuild").with(csrf()).session(userSession))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/admin/users/{userId}", userId).session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(userId))
                .andExpect(jsonPath("$.providers[0].apiKeyMasked").exists())
                .andExpect(content().string(not(containsString("sk-admin-secret-raw"))));

        mockMvc.perform(post("/api/admin/tasks/{jobId}/mark-failed", jobId).with(csrf())
                        .session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"manual recovery test\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.currentStep").value("ADMIN_MARKED_FAILED"));

        mockMvc.perform(get("/api/admin/tasks/failures").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("manual recovery test")));
    }

    private MockHttpSession register(String email, String nickname) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "%s",
                                  "password": "password123",
                                  "nickname": "%s"
                                }
                                """.formatted(email, nickname)))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private JsonNode upload(MockHttpSession session) throws Exception {
        String md5 = "%032x".formatted(System.nanoTime());
        return objectMapper.readTree(mockMvc.perform(post("/api/videos/upload/complete").with(csrf())
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "md5": "%s",
                                  "originalName": "admin-video.mp4",
                                  "durationMs": 1000
                                }
                                """.formatted(md5)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }
}
