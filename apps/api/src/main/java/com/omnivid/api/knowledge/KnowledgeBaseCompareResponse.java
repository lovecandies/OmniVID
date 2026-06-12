package com.omnivid.api.knowledge;

import java.util.List;

public record KnowledgeBaseCompareResponse(
        long knowledgeBaseId,
        String knowledgeBaseName,
        String question,
        int videoCount,
        List<String> sharedThemes,
        List<String> differences,
        List<KnowledgeBaseVideoViewpoint> viewpoints,
        List<KnowledgeBaseCompareCitation> citations
) {
}
