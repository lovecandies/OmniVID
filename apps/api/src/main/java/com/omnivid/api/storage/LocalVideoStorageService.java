package com.omnivid.api.storage;

import com.omnivid.api.common.ApiException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.DigestInputStream;
import java.util.HexFormat;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class LocalVideoStorageService {
    private static final int BUFFER_SIZE = 1024 * 1024;

    private final Path storageRoot;

    public LocalVideoStorageService(@Value("${omnivid.storage-root}") String storageRoot) {
        this.storageRoot = Paths.get(storageRoot).toAbsolutePath().normalize();
    }

    public StoredVideoFile store(MultipartFile file) {
        if (file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Upload file is empty");
        }

        String originalName = sanitizeOriginalName(file.getOriginalFilename());
        try {
            return storeInput(file.getInputStream(), originalName);
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to read upload file");
        }
    }

    public StoredVideoFile storeLocalFile(Path source, String originalName) {
        if (!Files.isRegularFile(source)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Downloaded video file is not available");
        }

        try {
            return storeInput(Files.newInputStream(source, StandardOpenOption.READ), sanitizeOriginalName(originalName));
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to read downloaded video file");
        }
    }

    private StoredVideoFile storeInput(InputStream source, String originalName) {
        Path tempFile = null;

        try {
            Files.createDirectories(storageRoot.resolve("tmp"));
            tempFile = Files.createTempFile(storageRoot.resolve("tmp"), "upload-", ".part");

            MessageDigest digest = MessageDigest.getInstance("MD5");
            long sizeBytes = copyAndDigest(source, tempFile, digest);
            if (sizeBytes <= 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Video file is empty");
            }
            String md5 = HexFormat.of().formatHex(digest.digest());

            Path videoDir = storageRoot.resolve("videos").resolve(md5).normalize();
            ensureInsideStorage(videoDir);
            Files.createDirectories(videoDir);

            Path finalPath = videoDir.resolve(originalName).normalize();
            ensureInsideStorage(finalPath);
            if (Files.notExists(finalPath)) {
                Files.move(tempFile, finalPath, StandardCopyOption.ATOMIC_MOVE);
                tempFile = null;
            }

            return new StoredVideoFile(originalName, md5, toLocalStoragePath(finalPath), finalPath, sizeBytes);
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to store upload file");
        } catch (NoSuchAlgorithmException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "MD5 digest is unavailable");
        } finally {
            deleteTempFile(tempFile);
        }
    }

    public Resource loadAsResource(String storagePath) {
        return new FileSystemResource(resolveLocalPath(storagePath));
    }

    public Path resolveLocalFile(String storagePath) {
        return resolveLocalPath(storagePath);
    }

    public StoredVideoFile loadStoredFile(String storagePath, String originalName, String md5) {
        Path localPath = resolveLocalPath(storagePath);
        try {
            return new StoredVideoFile(originalName, md5, storagePath, localPath, Files.size(localPath));
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to read stored video file");
        }
    }

    private Path resolveLocalPath(String storagePath) {
        if (storagePath == null || !storagePath.startsWith("local://")) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Video file is not available locally");
        }

        Path path = storageRoot.resolve(storagePath.substring("local://".length())).normalize();
        ensureInsideStorage(path);
        if (!Files.isRegularFile(path)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Video file not found");
        }
        return path;
    }

    private long copyAndDigest(InputStream source, Path tempFile, MessageDigest digest) throws IOException {
        long sizeBytes = 0;
        byte[] buffer = new byte[BUFFER_SIZE];
        try (
                InputStream input = new DigestInputStream(source, digest);
                OutputStream output = Files.newOutputStream(tempFile, StandardOpenOption.WRITE)
        ) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
                sizeBytes += read;
            }
        }
        return sizeBytes;
    }

    private String sanitizeOriginalName(String originalName) {
        String candidate = originalName == null || originalName.isBlank() ? "upload.mp4" : originalName;
        String filename = Paths.get(candidate).getFileName().toString();
        String sanitized = filename.replaceAll("[^a-zA-Z0-9._-]", "_");
        return sanitized.isBlank() ? "upload.mp4" : sanitized;
    }

    private void ensureInsideStorage(Path path) {
        if (!path.toAbsolutePath().normalize().startsWith(storageRoot)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid upload filename");
        }
    }

    private String toLocalStoragePath(Path path) {
        String relative = storageRoot.relativize(path.toAbsolutePath().normalize()).toString();
        return "local://" + relative.replace('\\', '/');
    }

    private void deleteTempFile(Path tempFile) {
        if (tempFile == null) {
            return;
        }
        try {
            Files.deleteIfExists(tempFile);
        } catch (IOException ignored) {
            // Best-effort cleanup only.
        }
    }
}
