package com.omnivid.api.security;

import com.omnivid.api.common.ApiException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class VideoUploadSecurityService {
    private static final Set<String> EXTENSIONS = Set.of("mp4", "mov", "mkv", "webm", "avi");
    private static final Set<String> GENERIC_CONTENT_TYPES = Set.of("", "application/octet-stream", "binary/octet-stream");
    private final long maxBytes;

    public VideoUploadSecurityService(@Value("${omnivid.security.upload.max-bytes:2147483648}") long maxBytes) {
        this.maxBytes = maxBytes;
    }

    public void validateMultipart(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Upload file is empty");
        }
        validateNameAndSize(file.getOriginalFilename(), file.getSize());
        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        if (!contentType.startsWith("video/")
                && !GENERIC_CONTENT_TYPES.contains(contentType)
                && !contentType.contains("matroska")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Upload content type is not a supported video");
        }
        try (InputStream input = file.getInputStream()) {
            validateSignature(input);
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Failed to inspect upload file");
        }
    }

    public void validateNameAndSize(String fileName, long sizeBytes) {
        if (sizeBytes <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Video file is empty");
        }
        if (sizeBytes > maxBytes) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "Video file exceeds the configured upload limit");
        }
        String extension = extension(fileName);
        if (!EXTENSIONS.contains(extension)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Unsupported video file extension");
        }
    }

    public void validateStoredFile(Path path, String fileName) {
        try {
            validateNameAndSize(fileName, Files.size(path));
            try (InputStream input = Files.newInputStream(path)) {
                validateSignature(input);
            }
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Failed to inspect stored video file");
        }
    }

    private void validateSignature(InputStream input) throws IOException {
        byte[] header = input.readNBytes(16);
        boolean isoBaseMedia = header.length >= 12
                && header[4] == 'f' && header[5] == 't' && header[6] == 'y' && header[7] == 'p';
        boolean ebml = header.length >= 4
                && (header[0] & 0xff) == 0x1a && (header[1] & 0xff) == 0x45
                && (header[2] & 0xff) == 0xdf && (header[3] & 0xff) == 0xa3;
        boolean avi = header.length >= 12
                && header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F'
                && header[8] == 'A' && header[9] == 'V' && header[10] == 'I' && header[11] == ' ';
        if (!isoBaseMedia && !ebml && !avi) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Upload file signature is not a supported video container");
        }
    }

    private String extension(String fileName) {
        String value = fileName == null ? "" : fileName.trim().toLowerCase(Locale.ROOT);
        int separator = value.lastIndexOf('.');
        return separator < 0 ? "" : value.substring(separator + 1);
    }
}
