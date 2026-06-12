package com.omnivid.api.llm;

import java.util.Optional;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/llm")
public class CloudLlmController {
    private final CloudLlmClient llm;
    private final LlmProviderService providers;

    public CloudLlmController(CloudLlmClient llm, LlmProviderService providers) {
        this.llm = llm;
        this.providers = providers;
    }

    @GetMapping("/config")
    CloudLlmConfigResponse config() {
        return llm.status();
    }

    @PostMapping("/config")
    CloudLlmConfigResponse configure(@RequestBody CloudLlmConfigRequest request) {
        return llm.configure(request);
    }

    @GetMapping("/providers")
    List<LlmProviderResponse> providers() {
        return providers.list();
    }

    @PostMapping("/providers")
    LlmProviderResponse saveProvider(@RequestBody LlmProviderSaveRequest request) {
        return providers.saveAndActivate(request);
    }

    @PostMapping("/providers/{id}/activate")
    LlmProviderResponse activateProvider(@PathVariable long id) {
        return providers.activate(id);
    }

    @PostMapping("/providers/{id}/rotate")
    LlmProviderResponse rotateProvider(@PathVariable long id, @RequestBody LlmProviderRotateRequest request) {
        return providers.rotateKey(id, request);
    }

    @PostMapping("/providers/{id}/disable")
    LlmProviderResponse disableProvider(@PathVariable long id) {
        return providers.disable(id);
    }

    @DeleteMapping("/providers/{id}")
    void deleteProvider(@PathVariable long id) {
        providers.delete(id);
    }

    @PostMapping("/test")
    CloudLlmTestResponse test() {
        if (!llm.available()) {
            CloudLlmTestResponse response = new CloudLlmTestResponse(
                    false,
                    "LLM 未启用或 API Key 未配置",
                    llm.model(),
                    0,
                    0,
                    0,
                    0
            );
            providers.updateTestResult("FAILED", response.message());
            return response;
        }

        Optional<CloudLlmResult> result = llm.complete(
                "你是 OmniVid 的连接测试助手。请只返回 OK。",
                "请返回 OK，用于确认模型接口可以连通。",
                16
        );
        if (result.isPresent()) {
            CloudLlmResult usage = result.get();
            CloudLlmTestResponse response = new CloudLlmTestResponse(
                    true,
                    "连接成功，模型已返回响应",
                    usage.model(),
                    usage.durationMs(),
                    usage.promptTokens(),
                    usage.completionTokens(),
                    usage.totalTokens()
            );
            providers.updateTestResult("OK", testMessage(response));
            return response;
        }
        CloudLlmTestResponse response = new CloudLlmTestResponse(
                false,
                "连接失败：请检查 API Key、Base URL、模型名或网络",
                llm.model(),
                0,
                0,
                0,
                0
        );
        providers.updateTestResult("FAILED", response.message());
        return response;
    }

    private String testMessage(CloudLlmTestResponse response) {
        if (response.totalTokens() <= 0) {
            return response.message() + " · " + response.durationMs() + "ms";
        }
        return response.message() + " · " + response.durationMs() + "ms · "
                + response.totalTokens() + " tokens";
    }
}
