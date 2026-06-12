package com.omnivid.api.upload;

import com.omnivid.api.common.ApiException;
import com.omnivid.api.job.ProcessingJob;
import com.omnivid.api.job.ProcessingJobRepository;
import com.omnivid.api.storage.LocalVideoStorageService;
import com.omnivid.api.storage.StoredUploadPart;
import com.omnivid.api.storage.StoredVideoFile;
import com.omnivid.api.video.CompleteUploadResponse;
import com.omnivid.api.video.VideoAsset;
import com.omnivid.api.video.VideoRepository;
import com.omnivid.api.video.VideoService;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ChunkUploadService {
    private static final long DEMO_USER_ID = 1L;
    private static final long MIN_PART_SIZE = 256L * 1024L;
    private static final long MAX_PART_SIZE = 64L * 1024L * 1024L;

    private final ChunkUploadRepository uploads;
    private final LocalVideoStorageService storage;
    private final VideoService videos;
    private final VideoRepository videoRepository;
    private final ProcessingJobRepository jobs;

    public ChunkUploadService(
            ChunkUploadRepository uploads,
            LocalVideoStorageService storage,
            VideoService videos,
            VideoRepository videoRepository,
            ProcessingJobRepository jobs
    ) {
        this.uploads = uploads;
        this.storage = storage;
        this.videos = videos;
        this.videoRepository = videoRepository;
        this.jobs = jobs;
    }

    public ChunkUploadSessionResponse createSession(ChunkUploadCreateRequest request) {
        String fileName = sanitizeFileName(request.fileName());
        String fileMd5 = normalizeMd5(request.fileMd5());
        long fileSize = requirePositive(request.fileSize(), "fileSize");
        long partSize = normalizePartSize(request.partSize());
        int totalParts = totalParts(fileSize, partSize, request.totalParts());

        CompleteUploadResponse deduplicated = deduplicatedUpload(fileMd5);
        if (deduplicated != null) {
            return new ChunkUploadSessionResponse(
                    "",
                    fileName,
                    fileSize,
                    fileMd5,
                    partSize,
                    totalParts,
                    fileSize,
                    "DEDUPLICATED",
                    List.of(),
                    List.of(),
                    true,
                    deduplicated
            );
        }

        ChunkUploadSession session = uploads.findReusable(DEMO_USER_ID, fileMd5, fileName, fileSize, partSize, totalParts)
                .orElseGet(() -> {
                    ChunkUploadSession created = new ChunkUploadSession(
                            UUID.randomUUID().toString(),
                            DEMO_USER_ID,
                            fileName,
                            fileSize,
                            fileMd5,
                            partSize,
                            totalParts,
                            0,
                            "UPLOADING"
                    );
                    uploads.create(created);
                    return created;
                });
        return response(session, false, null);
    }

    public ChunkUploadSessionResponse status(String sessionId) {
        ChunkUploadSession session = requireSession(sessionId);
        return response(session, false, null);
    }

    public ChunkUploadPartResponse uploadPart(String sessionId, int partNumber, MultipartFile file, String expectedPartMd5) {
        ChunkUploadSession session = requireSession(sessionId);
        if (!"UPLOADING".equals(session.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "Upload session is not accepting parts: " + session.status());
        }
        if (partNumber < 0 || partNumber >= session.totalParts()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid upload part number");
        }
        if (file == null || file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Upload part is empty");
        }

        StoredUploadPart storedPart;
        try {
            storedPart = storage.storeUploadPart(session.id(), partNumber, file.getInputStream());
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to read upload part");
        }
        validatePartSize(session, storedPart);
        if (expectedPartMd5 != null && !expectedPartMd5.isBlank()
                && !storedPart.md5().equalsIgnoreCase(expectedPartMd5.trim())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Upload part MD5 mismatch");
        }

        uploads.upsertPart(new ChunkUploadPart(
                session.id(),
                partNumber,
                storedPart.sizeBytes(),
                storedPart.md5(),
                storedPart.storagePath()
        ));

        ChunkUploadSession refreshed = requireSession(session.id());
        List<Integer> uploadedParts = uploadedParts(session.id());
        return new ChunkUploadPartResponse(
                session.id(),
                partNumber,
                storedPart.md5(),
                storedPart.sizeBytes(),
                refreshed.uploadedBytes(),
                refreshed.status(),
                uploadedParts,
                missingParts(uploadedParts, session.totalParts())
        );
    }

    public ChunkUploadCompleteResponse complete(String sessionId) {
        ChunkUploadSession session = requireSession(sessionId);
        if (!"UPLOADING".equals(session.status()) && !"COMPLETING".equals(session.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "Upload session cannot be completed from status: " + session.status());
        }
        List<Integer> uploadedParts = uploadedParts(session.id());
        List<Integer> missingParts = missingParts(uploadedParts, session.totalParts());
        if (!missingParts.isEmpty()) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "Upload parts are missing",
                    "Continue uploading missing parts before complete",
                    "missing=" + missingParts
            );
        }

        uploads.markStatus(session.id(), "COMPLETING");
        try {
            StoredVideoFile storedFile = storage.mergeUploadParts(session.id(), session.fileName(), session.totalParts());
            if (!storedFile.md5().equalsIgnoreCase(session.fileMd5())) {
                uploads.markStatus(session.id(), "FAILED");
                throw new ApiException(HttpStatus.BAD_REQUEST, "Merged file MD5 mismatch");
            }
            CompleteUploadResponse upload = videos.completeStoredUpload(storedFile);
            uploads.markStatus(session.id(), "DONE");
            return new ChunkUploadCompleteResponse(response(requireSession(session.id()), upload.deduplicated(), upload), upload);
        } catch (ApiException exception) {
            if (!"FAILED".equals(requireSession(session.id()).status())) {
                uploads.markStatus(session.id(), "FAILED");
            }
            throw exception;
        }
    }

    private CompleteUploadResponse deduplicatedUpload(String fileMd5) {
        VideoAsset existing = videoRepository.findByMd5(fileMd5).orElse(null);
        if (existing == null) {
            return null;
        }
        ProcessingJob job = jobs.findLatestByVideoId(existing.id())
                .orElseGet(() -> jobs.create(existing.id()));
        return new CompleteUploadResponse(true, existing, job);
    }

    private ChunkUploadSession requireSession(String sessionId) {
        return uploads.findSession(sessionId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Upload session not found"));
    }

    private ChunkUploadSessionResponse response(ChunkUploadSession session, boolean deduplicated, CompleteUploadResponse upload) {
        List<Integer> uploadedParts = uploadedParts(session.id());
        return new ChunkUploadSessionResponse(
                session.id(),
                session.fileName(),
                session.fileSize(),
                session.fileMd5(),
                session.partSize(),
                session.totalParts(),
                session.uploadedBytes(),
                session.status(),
                uploadedParts,
                missingParts(uploadedParts, session.totalParts()),
                deduplicated,
                upload
        );
    }

    private List<Integer> uploadedParts(String sessionId) {
        return uploads.listParts(sessionId).stream()
                .map(ChunkUploadPart::partNumber)
                .sorted()
                .toList();
    }

    private List<Integer> missingParts(List<Integer> uploadedParts, int totalParts) {
        Set<Integer> uploaded = new HashSet<>(uploadedParts);
        return java.util.stream.IntStream.range(0, totalParts)
                .filter(part -> !uploaded.contains(part))
                .boxed()
                .toList();
    }

    private void validatePartSize(ChunkUploadSession session, StoredUploadPart part) {
        long expected = part.partNumber() == session.totalParts() - 1
                ? session.fileSize() - session.partSize() * (session.totalParts() - 1L)
                : session.partSize();
        if (part.sizeBytes() != expected) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "Upload part size mismatch",
                    "Part size does not match the upload session plan",
                    "part=" + part.partNumber() + ", expected=" + expected + ", actual=" + part.sizeBytes()
            );
        }
    }

    private String normalizeMd5(String value) {
        String md5 = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!md5.matches("[a-f0-9]{32}")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "fileMd5 must be a 32-character lowercase MD5");
        }
        return md5;
    }

    private String sanitizeFileName(String fileName) {
        String candidate = fileName == null || fileName.isBlank() ? "upload.mp4" : fileName;
        String name = Paths.get(candidate).getFileName().toString();
        String sanitized = name.replaceAll("[^a-zA-Z0-9._-]", "_");
        return sanitized.isBlank() ? "upload.mp4" : sanitized;
    }

    private long requirePositive(long value, String field) {
        if (value <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, field + " must be positive");
        }
        return value;
    }

    private long normalizePartSize(long value) {
        long partSize = requirePositive(value, "partSize");
        if (partSize < MIN_PART_SIZE || partSize > MAX_PART_SIZE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "partSize must be between 256KB and 64MB");
        }
        return partSize;
    }

    private int totalParts(long fileSize, long partSize, Integer requestedTotalParts) {
        int computed = (int) ((fileSize + partSize - 1) / partSize);
        if (computed <= 0 || computed > 100_000) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid upload part count");
        }
        if (requestedTotalParts != null && requestedTotalParts > 0 && requestedTotalParts != computed) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "totalParts does not match fileSize and partSize");
        }
        return computed;
    }
}
