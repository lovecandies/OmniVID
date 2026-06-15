package com.omnivid.api.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.containsString;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "omnivid.security.admin-emails=admin@example.com",
        "omnivid.security.login-rate-limit.max-failures=3"
})
@AutoConfigureMockMvc
class SecurityHardeningTests {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void unsafeRequestsRequireCsrfToken() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "csrf@example.com",
                                  "password": "password123",
                                  "nickname": "CSRF"
                                }
                                """))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isOk());
    }

    @Test
    void diagnosticsRequireAdminRole() throws Exception {
        MockHttpSession userSession = register("ordinary@example.com", "Ordinary");
        MockHttpSession adminSession = register("admin@example.com", "Admin");

        mockMvc.perform(get("/api/runtime/status").session(userSession))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/runtime/status").session(adminSession))
                .andExpect(status().isOk());
    }

    @Test
    void repeatedFailedLoginsAreRateLimited() throws Exception {
        String body = """
                {
                  "email": "rate-limit@example.com",
                  "password": "wrong-password"
                }
                """;
        for (int attempt = 0; attempt < 3; attempt++) {
            mockMvc.perform(post("/api/auth/login").with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.detail", containsString(attempt == 2 ? "captchaRequired=true" : "captchaRequired=false")));
        }
        mockMvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void renamedTextFileIsRejectedAsVideo() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "attack.mp4",
                MediaType.TEXT_PLAIN_VALUE,
                "not a video".getBytes()
        );
        mockMvc.perform(multipart("/api/videos/upload/file")
                        .file(file)
                        .with(csrf())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(
                                "user@example.com").roles("USER")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void productionRejectsPlaceholderSecrets() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("production");
        ProductionSecurityValidator validator = new ProductionSecurityValidator(
                environment,
                "change-me",
                "",
                "omnivid_pass",
                "http://example.com"
        );
        assertThatThrownBy(validator::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class);
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
}
