package com.omnivid.api.agent;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/knowledge-bases/default/agent")
public class KnowledgeBaseAgentController {
    private final AgentService service;

    public KnowledgeBaseAgentController(AgentService service) {
        this.service = service;
    }

    @PostMapping("/ask")
    AgentAskResponse ask(@Valid @RequestBody AgentAskRequest request) {
        return service.askDefaultKnowledgeBase(request);
    }
}
