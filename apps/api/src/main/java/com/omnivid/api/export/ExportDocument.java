package com.omnivid.api.export;

import java.util.List;

public record ExportDocument(
        String title,
        String subtitle,
        String executiveSummary,
        List<ExportSection> sections,
        List<String> actionItems,
        List<String> sourceNotes
) {
}
