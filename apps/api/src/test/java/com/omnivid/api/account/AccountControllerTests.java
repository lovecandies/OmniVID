package com.omnivid.api.account;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.blankOrNullString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

@SpringBootTest
@AutoConfigureMockMvc
class AccountControllerTests {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void emailVerificationPasswordResetAndChangePasswordWorkFromApi() throws Exception {
        String email = "account-" + System.nanoTime() + "@example.com";
        MockHttpSession session = register(email, "Account User", "password123");

        JsonNode verify = postJson(session, "/api/account/email/verification/request", "{}");
        String verifyToken = verify.path("devToken").asText();
        mockMvc.perform(post("/api/account/email/verification/confirm").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"%s\"}".formatted(verifyToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Email verified"));

        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.emailVerified").value(true));

        mockMvc.perform(post("/api/account/password/change").with(csrf())
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "currentPassword": "password123",
                                  "newPassword": "password456"
                                }
                                """))
                .andExpect(status().isOk());

        JsonNode forgot = objectMapper.readTree(mockMvc.perform(post("/api/account/password/forgot").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\"}".formatted(email)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        String resetToken = forgot.path("devToken").asText();
        mockMvc.perform(post("/api/account/password/reset").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "token": "%s",
                                  "newPassword": "password789"
                                }
                                """.formatted(resetToken)))
                .andExpect(status().isOk());

        login(email, "password456").andExpect(status().isUnauthorized());
        login(email, "password789").andExpect(status().isOk());
    }

    @Test
    void sessionsExportAndDeleteAreSelfScoped() throws Exception {
        String email = "self-" + System.nanoTime() + "@example.com";
        MockHttpSession session = register(email, "Self User", "password123");

        mockMvc.perform(get("/api/account/sessions").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].principalName").value(email))
                .andExpect(jsonPath("$[0].current").value(true));

        mockMvc.perform(get("/api/account/export").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value(email))
                .andExpect(jsonPath("$.providers.llm").isArray());

        mockMvc.perform(delete("/api/account").with(csrf())
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true));

        login(email, "password123").andExpect(status().isUnauthorized());
    }

    private MockHttpSession register(String email, String nickname, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "%s",
                                  "password": "%s",
                                  "nickname": "%s"
                                }
                                """.formatted(email, password, nickname)))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private JsonNode postJson(MockHttpSession session, String path, String body) throws Exception {
        return objectMapper.readTree(mockMvc.perform(post(path).with(csrf())
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.devToken", not(blankOrNullString())))
                .andReturn().getResponse().getContentAsString());
    }

    private org.springframework.test.web.servlet.ResultActions login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "email": "%s",
                          "password": "%s"
                        }
                        """.formatted(email, password)));
    }
}
