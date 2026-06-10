package com.omnivid.api.knowledge;

import com.omnivid.api.video.VideoAsset;
import java.util.List;

public record KnowledgeBaseDetailResponse(
        KnowledgeBase knowledgeBase,
        List<VideoAsset> videos
) {
    public KnowledgeBaseDetailResponse {
        videos = videos == null ? List.of() : List.copyOf(videos);
    }
}
