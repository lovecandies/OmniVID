package com.omnivid.api.agent.retrieval;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/embedding")
public class EmbeddingProviderController {
    private final EmbeddingProviderService providers;

    public EmbeddingProviderController(EmbeddingProviderService providers) {
        this.providers = providers;
    }

    @GetMapping("/providers")
    List<EmbeddingProviderResponse> providers() {
        return providers.list();
    }

    @PostMapping("/providers")
    EmbeddingProviderResponse saveProvider(@RequestBody EmbeddingProviderSaveRequest request) {
        return providers.saveAndActivate(request);
    }

    @PostMapping("/providers/{id}/activate")
    EmbeddingProviderResponse activateProvider(@PathVariable long id) {
        return providers.activate(id);
    }

    @PostMapping("/test")
    EmbeddingProviderTestResponse test() {
        return providers.testActive();
    }
}
