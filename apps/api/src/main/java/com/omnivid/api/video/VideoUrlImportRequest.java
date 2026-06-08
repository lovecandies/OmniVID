package com.omnivid.api.video;

import jakarta.validation.constraints.NotBlank;

public record VideoUrlImportRequest(
        @NotBlank(message = "URL is required")
        String url,
        String cookiesFile,
        String cookiesFromBrowser
) {
}
