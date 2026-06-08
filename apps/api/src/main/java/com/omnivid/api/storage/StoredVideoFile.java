package com.omnivid.api.storage;

import java.nio.file.Path;

public record StoredVideoFile(
        String originalName,
        String md5,
        String storagePath,
        Path localPath,
        long sizeBytes
) {
}
