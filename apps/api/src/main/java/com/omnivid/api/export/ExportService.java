package com.omnivid.api.export;

import java.text.Normalizer;
import org.springframework.stereotype.Service;

@Service
public class ExportService {
    private final ExportDocumentGenerator generator;
    private final ExportRenderer renderer;

    public ExportService(ExportDocumentGenerator generator, ExportRenderer renderer) {
        this.generator = generator;
        this.renderer = renderer;
    }

    public GeneratedExport generate(long videoId, ExportRequest request) {
        ExportDocumentGenerator.DocumentGeneration generated = generator.generate(videoId, request.summaryType());
        byte[] content = renderer.render(generated.document(), request.format());
        String filename = safeFilename(generated.document().title()) + "." + request.format().extension();
        return new GeneratedExport(
                content,
                filename,
                request.format().mediaType(),
                generated.model(),
                generated.mode()
        );
    }

    private String safeFilename(String title) {
        String normalized = Normalizer.normalize(title, Normalizer.Form.NFKC)
                .replaceAll("[\\\\/:*?\"<>|\\r\\n]+", "-")
                .replaceAll("\\s+", " ")
                .trim();
        return normalized.isBlank() ? "omnivid-export" : normalized.substring(0, Math.min(80, normalized.length()));
    }
}
