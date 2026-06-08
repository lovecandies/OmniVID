package com.omnivid.api.summary;

public record SummaryAsset(
        long id,
        long videoId,
        String type,
        String title,
        String contentJson,
        String modelName,
        String promptVersion
) {
}
