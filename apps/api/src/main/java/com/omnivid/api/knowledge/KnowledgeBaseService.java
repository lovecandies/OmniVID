package com.omnivid.api.knowledge;

import com.omnivid.api.common.ApiException;
import com.omnivid.api.video.VideoService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeBaseService {
    private final KnowledgeBaseRepository knowledgeBases;
    private final VideoService videos;

    public KnowledgeBaseService(KnowledgeBaseRepository knowledgeBases, VideoService videos) {
        this.knowledgeBases = knowledgeBases;
        this.videos = videos;
    }

    public List<KnowledgeBase> list() {
        return knowledgeBases.list();
    }

    public KnowledgeBase create(KnowledgeBaseCreateRequest request) {
        String name = requireName(request.name());
        String description = request.description() == null ? "" : request.description().trim();
        return knowledgeBases.create(name, description);
    }

    public KnowledgeBaseDetailResponse detail(long id) {
        KnowledgeBase knowledgeBase = requireKnowledgeBase(id);
        return new KnowledgeBaseDetailResponse(knowledgeBase, knowledgeBases.videos(id));
    }

    public KnowledgeBaseDetailResponse addVideo(long id, KnowledgeBaseVideoRequest request) {
        requireKnowledgeBase(id);
        videos.requireVideo(request.videoId());
        knowledgeBases.addVideo(id, request.videoId());
        return detail(id);
    }

    public KnowledgeBaseDetailResponse removeVideo(long id, long videoId) {
        requireKnowledgeBase(id);
        knowledgeBases.removeVideo(id, videoId);
        return detail(id);
    }

    public void delete(long id) {
        requireKnowledgeBase(id);
        knowledgeBases.delete(id);
    }

    public List<Long> videoIds(long id) {
        requireKnowledgeBase(id);
        return knowledgeBases.videoIds(id);
    }

    public KnowledgeBase requireKnowledgeBase(long id) {
        return knowledgeBases.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Knowledge base not found"));
    }

    private String requireName(String value) {
        if (value == null || value.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Knowledge base name is required");
        }
        return value.trim();
    }
}
