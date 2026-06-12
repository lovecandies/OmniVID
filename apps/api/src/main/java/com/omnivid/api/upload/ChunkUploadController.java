package com.omnivid.api.upload;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/videos/upload/chunked")
public class ChunkUploadController {
    private final ChunkUploadService uploads;

    public ChunkUploadController(ChunkUploadService uploads) {
        this.uploads = uploads;
    }

    @PostMapping("/sessions")
    ChunkUploadSessionResponse createSession(@RequestBody ChunkUploadCreateRequest request) {
        return uploads.createSession(request);
    }

    @GetMapping("/sessions/{sessionId}")
    ChunkUploadSessionResponse status(@PathVariable String sessionId) {
        return uploads.status(sessionId);
    }

    @PostMapping(value = "/sessions/{sessionId}/parts/{partNumber}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ChunkUploadPartResponse uploadPart(
            @PathVariable String sessionId,
            @PathVariable int partNumber,
            @RequestPart("part") MultipartFile part,
            @RequestParam(value = "partMd5", required = false) String partMd5
    ) {
        return uploads.uploadPart(sessionId, partNumber, part, partMd5);
    }

    @PostMapping("/sessions/{sessionId}/complete")
    ChunkUploadCompleteResponse complete(@PathVariable String sessionId) {
        return uploads.complete(sessionId);
    }
}
