package com.omnivid.api.video;

import com.omnivid.api.summary.SummaryAsset;
import com.omnivid.api.progress.ProgressSnapshot;
import com.omnivid.api.storage.LocalVideoStorageService;
import com.omnivid.api.storage.StoredVideoFile;
import com.omnivid.api.security.VideoUploadSecurityService;
import com.omnivid.api.transcript.TranscriptEditRequest;
import com.omnivid.api.transcript.TranscriptSegment;
import com.omnivid.api.transcript.TranscriptVersionDetailResponse;
import com.omnivid.api.transcript.TranscriptVersionResponse;
import jakarta.validation.Valid;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.beans.factory.annotation.Value;
import com.omnivid.api.common.ApiException;

@RestController
@RequestMapping("/api/videos")
public class VideoController {
    private final VideoService service;
    private final LocalVideoStorageService storage;
    private final VideoUrlImportService urlImportService;
    private final VideoUploadSecurityService uploadSecurity;
    private final boolean compatibilityUploadEnabled;

    public VideoController(
            VideoService service,
            LocalVideoStorageService storage,
            VideoUrlImportService urlImportService,
            VideoUploadSecurityService uploadSecurity,
            @Value("${omnivid.security.compatibility-upload-enabled:true}") boolean compatibilityUploadEnabled
    ) {
        this.service = service;
        this.storage = storage;
        this.urlImportService = urlImportService;
        this.uploadSecurity = uploadSecurity;
        this.compatibilityUploadEnabled = compatibilityUploadEnabled;
    }

    @PostMapping("/upload/complete")
    CompleteUploadResponse completeUpload(@Valid @RequestBody CompleteUploadRequest request) {
        if (!compatibilityUploadEnabled) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Compatibility upload endpoint is disabled");
        }
        return service.completeUpload(request);
    }

    @PostMapping(value = "/upload/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    CompleteUploadResponse uploadFile(@RequestPart("file") MultipartFile file) {
        uploadSecurity.validateMultipart(file);
        StoredVideoFile storedFile = storage.store(file);
        return service.completeStoredUpload(storedFile);
    }

    @PostMapping("/import/url")
    CompleteUploadResponse importUrl(@Valid @RequestBody VideoUrlImportRequest request) {
        return urlImportService.importUrl(request.url(), request.cookiesFile(), request.cookiesFromBrowser());
    }

    @GetMapping
    List<VideoAsset> list() {
        return service.listVideos();
    }

    @GetMapping("/{videoId}")
    VideoDetailResponse detail(@PathVariable long videoId) {
        return service.detail(videoId);
    }

    @PostMapping("/{videoId}/retry")
    CompleteUploadResponse retry(@PathVariable long videoId) {
        return service.retryFailedVideo(videoId);
    }

    @GetMapping("/{videoId}/media")
    ResponseEntity<?> media(@PathVariable long videoId, @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader) throws IOException {
        VideoAsset video = service.requireVideo(videoId);
        Resource resource = storage.loadAsResource(video.storagePath());
        MediaType mediaType = MediaTypeFactory.getMediaType(resource).orElse(MediaType.APPLICATION_OCTET_STREAM);

        if (rangeHeader != null) {
            return rangedMedia(resource, mediaType, rangeHeader);
        }

        return ResponseEntity.ok()
                .contentType(mediaType)
                .contentLength(resource.contentLength())
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .body(resource);
    }

    private ResponseEntity<InputStreamResource> rangedMedia(Resource resource, MediaType mediaType, String rangeHeader) throws IOException {
        long totalLength = resource.contentLength();
        HttpRange range = HttpRange.parseRanges(rangeHeader).getFirst();
        long start = range.getRangeStart(totalLength);
        long end = range.getRangeEnd(totalLength);
        long rangeLength = Math.min(end - start + 1, totalLength - start);

        InputStream input = resource.getInputStream();
        input.skipNBytes(start);
        return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                .contentType(mediaType)
                .contentLength(rangeLength)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CONTENT_RANGE, "bytes %d-%d/%d".formatted(start, start + rangeLength - 1, totalLength))
                .body(new InputStreamResource(new BoundedInputStream(input, rangeLength)));
    }

    private static class BoundedInputStream extends FilterInputStream {
        private long remaining;

        BoundedInputStream(InputStream input, long limit) {
            super(input);
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int value = super.read();
            if (value != -1) {
                remaining--;
            }
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int read = super.read(bytes, offset, (int) Math.min(length, remaining));
            if (read != -1) {
                remaining -= read;
            }
            return read;
        }
    }

    @GetMapping("/{videoId}/transcripts")
    List<TranscriptSegment> transcripts(@PathVariable long videoId) {
        return service.transcripts(videoId);
    }

    @GetMapping("/{videoId}/transcripts/search")
    List<TranscriptSegment> searchTranscripts(@PathVariable long videoId, @RequestParam("q") String keyword) {
        return service.searchTranscripts(videoId, keyword);
    }

    @GetMapping("/{videoId}/transcripts/versions")
    List<TranscriptVersionResponse> transcriptVersions(@PathVariable long videoId) {
        return service.transcriptVersions(videoId);
    }

    @GetMapping("/{videoId}/transcripts/versions/{versionId}")
    TranscriptVersionDetailResponse transcriptVersionDetail(@PathVariable long videoId, @PathVariable long versionId) {
        return service.transcriptVersionDetail(videoId, versionId);
    }

    @PatchMapping("/{videoId}/transcripts/{segmentId}")
    VideoDetailResponse editTranscript(
            @PathVariable long videoId,
            @PathVariable long segmentId,
            @Valid @RequestBody TranscriptEditRequest request
    ) {
        return service.editTranscriptSegment(videoId, segmentId, request);
    }

    @PostMapping("/{videoId}/transcripts/versions/{versionId}/restore")
    VideoDetailResponse restoreTranscriptVersion(@PathVariable long videoId, @PathVariable long versionId) {
        return service.restoreTranscriptVersion(videoId, versionId);
    }

    @GetMapping("/{videoId}/summaries")
    List<SummaryAsset> summaries(@PathVariable long videoId) {
        return service.summaries(videoId);
    }

    @GetMapping("/{videoId}/progress")
    ProgressSnapshot progress(@PathVariable long videoId) {
        return service.progress(videoId);
    }

    @GetMapping(value = "/{videoId}/progress/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter progressStream(@PathVariable long videoId) {
        return service.progressStream(videoId);
    }
}
