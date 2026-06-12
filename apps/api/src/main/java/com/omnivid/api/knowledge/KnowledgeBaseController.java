package com.omnivid.api.knowledge;

import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/knowledge-bases")
public class KnowledgeBaseController {
    private final KnowledgeBaseService service;

    public KnowledgeBaseController(KnowledgeBaseService service) {
        this.service = service;
    }

    @GetMapping
    List<KnowledgeBase> list() {
        return service.list();
    }

    @PostMapping
    KnowledgeBase create(@RequestBody KnowledgeBaseCreateRequest request) {
        return service.create(request);
    }

    @GetMapping("/{id}")
    KnowledgeBaseDetailResponse detail(@PathVariable long id) {
        return service.detail(id);
    }

    @GetMapping("/{id}/coverage")
    KnowledgeBaseCoverageResponse coverage(@PathVariable long id) {
        return service.coverage(id);
    }

    @PostMapping("/{id}/compare")
    KnowledgeBaseCompareResponse compare(@PathVariable long id, @RequestBody KnowledgeBaseCompareRequest request) {
        return service.compare(id, request);
    }

    @DeleteMapping("/{id}")
    void delete(@PathVariable long id) {
        service.delete(id);
    }

    @PostMapping("/{id}/videos")
    KnowledgeBaseDetailResponse addVideo(@PathVariable long id, @RequestBody KnowledgeBaseVideoRequest request) {
        return service.addVideo(id, request);
    }

    @DeleteMapping("/{id}/videos/{videoId}")
    KnowledgeBaseDetailResponse removeVideo(@PathVariable long id, @PathVariable long videoId) {
        return service.removeVideo(id, videoId);
    }
}
