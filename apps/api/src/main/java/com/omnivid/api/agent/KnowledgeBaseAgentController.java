package com.omnivid.api.agent;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/knowledge-bases")
public class KnowledgeBaseAgentController {
    private final AgentService service;

    public KnowledgeBaseAgentController(AgentService service) {
        this.service = service;
    }

    @PostMapping("/default/agent/ask")
    AgentAskResponse askDefault(@Valid @RequestBody AgentAskRequest request) {
        return service.askDefaultKnowledgeBase(request);
    }

    @PostMapping("/{knowledgeBaseId}/agent/ask")
    AgentAskResponse askKnowledgeBase(
            @PathVariable long knowledgeBaseId,
            @Valid @RequestBody AgentAskRequest request
    ) {
        return service.askKnowledgeBase(knowledgeBaseId, request);
    }
}
