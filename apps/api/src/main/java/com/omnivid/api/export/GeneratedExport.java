package com.omnivid.api.export;

public record GeneratedExport(
        byte[] content,
        String filename,
        String mediaType,
        String model,
        String generationMode
) {
}
