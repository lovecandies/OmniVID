package com.omnivid.api.video;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;

public record CompleteUploadRequest(
        @NotBlank String originalName,
        @NotBlank @Pattern(regexp = "^[a-fA-F0-9]{32}$") String md5,
        @PositiveOrZero long durationMs
) {
}
