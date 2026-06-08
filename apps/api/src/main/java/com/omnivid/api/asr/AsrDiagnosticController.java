package com.omnivid.api.asr;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/videos/{videoId}/asr")
public class AsrDiagnosticController {
    private final AsrDiagnosticService diagnostics;

    public AsrDiagnosticController(AsrDiagnosticService diagnostics) {
        this.diagnostics = diagnostics;
    }

    @GetMapping("/diagnostics")
    AsrDiagnosticResponse inspect(@PathVariable long videoId) {
        return diagnostics.inspect(videoId);
    }
}
