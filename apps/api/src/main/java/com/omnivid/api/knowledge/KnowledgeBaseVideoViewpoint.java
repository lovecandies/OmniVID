package com.omnivid.api.knowledge;

import java.util.List;

public record KnowledgeBaseVideoViewpoint(
        long videoId,
        String originalName,
        String viewpoint,
        List<KnowledgeBaseCompareCitation> citations
) {
}
