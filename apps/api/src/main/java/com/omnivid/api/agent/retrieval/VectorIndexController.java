package com.omnivid.api.agent.retrieval;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/vector-index")
public class VectorIndexController {
    private final VectorIndexService service;

    public VectorIndexController(VectorIndexService service) {
        this.service = service;
    }

    @PostMapping("/rebuild")
    VectorIndexRebuildResponse rebuild() {
        return service.rebuildDefaultKnowledgeBase();
    }

    @GetMapping("/status")
    VectorIndexStatusResponse status() {
        return service.status();
    }
}
