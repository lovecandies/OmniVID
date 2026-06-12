package com.omnivid.api.export;

import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/videos/{videoId}/exports")
public class ExportController {
    private final ExportService exports;

    public ExportController(ExportService exports) {
        this.exports = exports;
    }

    @PostMapping
    ResponseEntity<byte[]> generate(@PathVariable long videoId, @Valid @RequestBody ExportRequest request) {
        GeneratedExport generated = exports.generate(videoId, request);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(generated.filename(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(generated.mediaType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-OmniVid-Model", generated.model())
                .header("X-OmniVid-Generation-Mode", generated.generationMode())
                .contentLength(generated.content().length)
                .body(generated.content());
    }
}
