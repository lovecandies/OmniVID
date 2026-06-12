package com.omnivid.api.agent.retrieval;

import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rerank")
public class RerankProviderController {
    private final RerankProviderService providers;

    public RerankProviderController(RerankProviderService providers) {
        this.providers = providers;
    }

    @GetMapping("/providers")
    List<RerankProviderResponse> providers() {
        return providers.list();
    }

    @PostMapping("/providers")
    RerankProviderResponse saveProvider(@RequestBody RerankProviderSaveRequest request) {
        return providers.saveAndActivate(request);
    }

    @PostMapping("/providers/{id}/activate")
    RerankProviderResponse activateProvider(@PathVariable long id) {
        return providers.activate(id);
    }

    @PostMapping("/providers/{id}/rotate")
    RerankProviderResponse rotateProvider(@PathVariable long id, @RequestBody RerankProviderRotateRequest request) {
        return providers.rotateKey(id, request);
    }

    @PostMapping("/providers/{id}/disable")
    RerankProviderResponse disableProvider(@PathVariable long id) {
        return providers.disable(id);
    }

    @DeleteMapping("/providers/{id}")
    void deleteProvider(@PathVariable long id) {
        providers.delete(id);
    }

    @PostMapping("/test")
    RerankProviderTestResponse test() {
        return providers.testActive();
    }
}
