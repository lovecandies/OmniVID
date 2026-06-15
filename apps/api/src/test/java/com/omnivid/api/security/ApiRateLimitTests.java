package com.omnivid.api.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "omnivid.security.api-rate-limit.max-requests=2",
        "omnivid.security.api-rate-limit.window=1m"
})
@AutoConfigureMockMvc
class ApiRateLimitTests {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void repeatedApiRequestsAreRateLimited() throws Exception {
        String ip = "203.0.113." + Math.floorMod(System.nanoTime(), 200);
        mockMvc.perform(get("/api/auth/csrf").header("X-Forwarded-For", ip))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/auth/csrf").header("X-Forwarded-For", ip))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/auth/csrf").header("X-Forwarded-For", ip))
                .andExpect(status().isTooManyRequests());
    }
}
