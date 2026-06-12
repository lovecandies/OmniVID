package com.omnivid.api;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.slf4j.MDC;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class ApiApplicationTests {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void contextLoads() {
	}

	@Test
	void traceHeaderIsReturnedAndVisibleInRuntimeStatus() throws Exception {
		mockMvc.perform(get("/api/runtime/status").header("X-Trace-Id", "integration-trace"))
				.andExpect(status().isOk())
				.andExpect(header().string("X-Trace-Id", "integration-trace"))
				.andExpect(jsonPath("$.observability.traceId").value("integration-trace"))
				.andExpect(jsonPath("$.observability.logFormat").value("json"));
		assertNull(MDC.get("traceId"));
		assertNull(MDC.get("status"));
		assertNull(MDC.get("durationMs"));
	}

}
