package com.omnivid.api.transcript;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TranscriptEditRequest(
        @NotBlank
        @Size(max = 2000)
        String content
) {
}
