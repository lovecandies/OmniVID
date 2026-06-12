package com.omnivid.api.export;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ExportRequest(
        @NotBlank String summaryType,
        @NotNull ExportFormat format
) {
}
