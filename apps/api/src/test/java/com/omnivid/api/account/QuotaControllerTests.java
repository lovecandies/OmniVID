package com.omnivid.api.account;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {
        "omnivid.account.default-max-video-count=1",
        "omnivid.account.default-max-knowledge-base-count=1"
})
@AutoConfigureMockMvc
class QuotaControllerTests {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void videoAndKnowledgeBaseQuotasAreEnforced() throws Exception {
        MockHttpSession session = register("quota-" + System.nanoTime() + "@example.com");
        upload(session, "%032x".formatted(System.nanoTime()), "first.mp4")
                .andExpect(status().isOk());

        upload(session, "%032x".formatted(System.nanoTime() + 1), "second.mp4")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.detail", containsString("videos=1/1")));

        createKnowledgeBase(session, "kb-a")
                .andExpect(status().isOk());
        createKnowledgeBase(session, "kb-b")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.detail", containsString("knowledgeBases=1/1")));

        mockMvc.perform(get("/api/account/quota").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.videoCount").value(1))
                .andExpect(jsonPath("$.maxVideoCount").value(1))
                .andExpect(jsonPath("$.knowledgeBaseCount").value(1));
    }

    private MockHttpSession register(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "%s",
                                  "password": "password123",
                                  "nickname": "Quota"
                                }
                                """.formatted(email)))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private org.springframework.test.web.servlet.ResultActions upload(MockHttpSession session, String md5, String name) throws Exception {
        return mockMvc.perform(post("/api/videos/upload/complete").with(csrf())
                .session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "md5": "%s",
                          "originalName": "%s",
                          "durationMs": 1000
                        }
                        """.formatted(md5, name)));
    }

    private org.springframework.test.web.servlet.ResultActions createKnowledgeBase(MockHttpSession session, String name) throws Exception {
        return mockMvc.perform(post("/api/knowledge-bases").with(csrf())
                .session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "name": "%s",
                          "description": "quota test"
                        }
                        """.formatted(name)));
    }
}
