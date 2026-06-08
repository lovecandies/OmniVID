package com.omnivid.api.agent;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/videos/{videoId}/agent")
public class AgentController {
    private final AgentService service;

    public AgentController(AgentService service) {
        this.service = service;
    }

    @PostMapping("/ask")
    AgentAskResponse ask(@PathVariable long videoId, @Valid @RequestBody AgentAskRequest request) {
        return service.ask(videoId, request);
    }

    @GetMapping("/messages")
    List<ChatMessage> messages(@PathVariable long videoId) {
        return service.messages(videoId);
    }

    @GetMapping("/context")
    AgentContextResponse context(@PathVariable long videoId) {
        return service.context(videoId);
    }

    @DeleteMapping("/messages")
    int clearMessages(@PathVariable long videoId) {
        return service.clearMessages(videoId);
    }
}
