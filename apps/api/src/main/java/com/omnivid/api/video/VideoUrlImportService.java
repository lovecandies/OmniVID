package com.omnivid.api.video;

import com.omnivid.api.common.ApiException;
import com.omnivid.api.storage.LocalVideoStorageService;
import com.omnivid.api.storage.StoredVideoFile;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class VideoUrlImportService {
    private static final Pattern URL_PATTERN = Pattern.compile("https?://\\S+");

    private final VideoService videos;
    private final LocalVideoStorageService storage;
    private final Path workRoot;
    private final String ytdlpPath;
    private final String ffmpegPath;
    private final Duration timeout;

    public VideoUrlImportService(
            VideoService videos,
            LocalVideoStorageService storage,
            @Value("${omnivid.storage-root}") String storageRoot,
            @Value("${omnivid.url-import.ytdlp-path:yt-dlp}") String ytdlpPath,
            @Value("${omnivid.ffmpeg.path}") String ffmpegPath,
            @Value("${omnivid.url-import.timeout:300s}") Duration timeout
    ) {
        this.videos = videos;
        this.storage = storage;
        this.workRoot = Paths.get(storageRoot).toAbsolutePath().normalize().resolve("tmp").resolve("url-import");
        this.ytdlpPath = ytdlpPath;
        this.ffmpegPath = ffmpegPath;
        this.timeout = timeout;
    }

    public CompleteUploadResponse importUrl(String input, String cookiesFile, String cookiesFromBrowser) {
        String url = normalizeUrl(input);
        String platform = requireSupportedPlatform(url);
        Path workDir = null;
        try {
            Files.createDirectories(workRoot);
            workDir = Files.createTempDirectory(workRoot, "source-");
            Path downloadedFile = download(url, platform, workDir, cookiesFile, cookiesFromBrowser);
            StoredVideoFile storedFile = storage.storeLocalFile(downloadedFile, downloadedFile.getFileName().toString());
            return videos.completeStoredUpload(storedFile);
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to prepare URL import workspace");
        } finally {
            deleteWorkDir(workDir);
        }
    }

    private Path download(String url, String platform, Path workDir, String cookiesFile, String cookiesFromBrowser) {
        Path logFile = workDir.resolve("yt-dlp.log");
        List<String> command = new ArrayList<>(List.of(
                ytdlpPath,
                "--no-playlist",
                "--user-agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
                "--referer",
                refererFor(platform),
                "--add-header",
                "Accept-Language: zh-CN,zh;q=0.9,en;q=0.8",
                "--ffmpeg-location",
                ffmpegPath,
                "--merge-output-format",
                "mp4",
                "--restrict-filenames",
                "-o",
                workDir.resolve("%(title).200B.%(ext)s").toString()
        ));
        appendCookieOptions(command, cookiesFile, cookiesFromBrowser);
        command.add(url);

        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(logFile.toFile())
                    .start();
            if (!process.waitFor(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new ApiException(HttpStatus.GATEWAY_TIMEOUT, platform + " URL download timed out");
            }
            if (process.exitValue() != 0) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, platform + " URL download failed: " + readLogTail(logFile));
            }
            return findDownloadedVideo(workDir)
                    .orElseThrow(() -> new ApiException(HttpStatus.BAD_GATEWAY, platform + " URL did not produce a video file"));
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "yt-dlp is not available; set OMNIVID_YTDLP_PATH or install yt-dlp");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "URL import was interrupted");
        }
    }

    private void appendCookieOptions(List<String> command, String cookiesFile, String cookiesFromBrowser) {
        String file = cookiesFile == null ? "" : cookiesFile.trim();
        if (!file.isBlank()) {
            Path path = Paths.get(file).toAbsolutePath().normalize();
            if (!Files.isRegularFile(path)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Cookie file does not exist: " + path);
            }
            command.add("--cookies");
            command.add(path.toString());
            return;
        }

        String browser = cookiesFromBrowser == null ? "" : cookiesFromBrowser.trim().toLowerCase(Locale.ROOT);
        if (browser.isBlank() || "none".equals(browser)) {
            return;
        }
        if (!List.of("chrome", "edge", "firefox").contains(browser)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "cookiesFromBrowser must be chrome, edge, firefox or none");
        }
        command.add("--cookies-from-browser");
        command.add(browser);
    }

    private Optional<Path> findDownloadedVideo(Path workDir) {
        try (Stream<Path> stream = Files.walk(workDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(this::isVideoFile)
                    .max(Comparator.comparingLong(this::sizeOrZero));
        } catch (IOException exception) {
            return Optional.empty();
        }
    }

    private String refererFor(String platform) {
        return switch (platform) {
            case "Bilibili" -> "https://www.bilibili.com/";
            case "Douyin" -> "https://www.douyin.com/";
            case "Xiaohongshu" -> "https://www.xiaohongshu.com/";
            default -> "https://www.bilibili.com/";
        };
    }

    private boolean isVideoFile(Path path) {
        String filename = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return filename.endsWith(".mp4")
                || filename.endsWith(".mkv")
                || filename.endsWith(".webm")
                || filename.endsWith(".mov");
    }

    private long sizeOrZero(Path path) {
        try {
            return Files.size(path);
        } catch (IOException exception) {
            return 0;
        }
    }

    private String normalizeUrl(String input) {
        String candidate = input == null ? "" : input.trim();
        Matcher matcher = URL_PATTERN.matcher(candidate);
        if (matcher.find()) {
            candidate = matcher.group();
        }
        candidate = candidate.replaceAll("[。），,\\])}>\"']+$", "");
        if (!candidate.startsWith("http://") && !candidate.startsWith("https://")) {
            candidate = "https://" + candidate;
        }
        return candidate;
    }

    private String requireSupportedPlatform(String url) {
        URI uri = parseUri(url);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Only HTTP/HTTPS video URLs are supported");
        }

        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (matchesHost(host, "bilibili.com") || matchesHost(host, "b23.tv")) {
            return "Bilibili";
        }
        if (matchesHost(host, "douyin.com") || matchesHost(host, "iesdouyin.com")) {
            return "Douyin";
        }
        if (matchesHost(host, "xiaohongshu.com") || matchesHost(host, "xhslink.com") || matchesHost(host, "xhs.cn")) {
            return "Xiaohongshu";
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, "Only Bilibili, Douyin and Xiaohongshu URLs are supported");
    }

    private URI parseUri(String url) {
        try {
            return new URI(url);
        } catch (URISyntaxException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid video URL");
        }
    }

    private boolean matchesHost(String host, String root) {
        return host.equals(root) || host.endsWith("." + root);
    }

    private String readLogTail(Path logFile) {
        try {
            if (!Files.isRegularFile(logFile)) {
                return "";
            }
            String text = Files.readString(logFile, StandardCharsets.UTF_8).replaceAll("\\s+", " ").trim();
            return text.length() <= 500 ? text : text.substring(text.length() - 500);
        } catch (IOException exception) {
            return "";
        }
    }

    private void deleteWorkDir(Path workDir) {
        if (workDir == null || !workDir.toAbsolutePath().normalize().startsWith(workRoot)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(workDir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best-effort cleanup only.
                }
            });
        } catch (IOException ignored) {
            // Best-effort cleanup only.
        }
    }
}
