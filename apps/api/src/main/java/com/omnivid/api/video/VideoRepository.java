package com.omnivid.api.video;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class VideoRepository {
    private final JdbcClient jdbc;

    public VideoRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<VideoAsset> findByMd5(String md5) {
        return jdbc.sql("SELECT * FROM video_asset WHERE md5 = :md5")
                .param("md5", md5)
                .query(this::map)
                .optional();
    }

    public Optional<VideoAsset> findByMd5AndUserId(String md5, long userId) {
        return jdbc.sql("""
                SELECT * FROM video_asset
                WHERE md5 = :md5 AND user_id = :userId
                """)
                .param("md5", md5)
                .param("userId", userId)
                .query(this::map)
                .optional();
    }

    public Optional<VideoAsset> findById(long id) {
        return jdbc.sql("SELECT * FROM video_asset WHERE id = :id")
                .param("id", id)
                .query(this::map)
                .optional();
    }

    public Optional<VideoAsset> findByIdAndUserId(long id, long userId) {
        return jdbc.sql("""
                SELECT * FROM video_asset
                WHERE id = :id AND user_id = :userId
                """)
                .param("id", id)
                .param("userId", userId)
                .query(this::map)
                .optional();
    }

    public List<VideoAsset> list(long userId) {
        return jdbc.sql("""
                SELECT * FROM video_asset
                WHERE user_id = :userId
                ORDER BY created_at DESC, id DESC
                LIMIT 30
                """)
                .param("userId", userId)
                .query(this::map)
                .list();
    }

    public VideoAsset insert(long userId, String md5, String originalName, String storagePath, long fileSizeBytes, long durationMs) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO video_asset (user_id, md5, original_name, storage_path, file_size_bytes, duration_ms, status)
                VALUES (:userId, :md5, :originalName, :storagePath, :fileSizeBytes, :durationMs, 'PROCESSING')
                """)
                .param("userId", userId)
                .param("md5", md5)
                .param("originalName", originalName)
                .param("storagePath", storagePath)
                .param("fileSizeBytes", Math.max(0, fileSizeBytes))
                .param("durationMs", durationMs)
                .update(keyHolder, "id");

        return findById(keyHolder.getKey().longValue()).orElseThrow();
    }

    public int countByUserId(long userId) {
        Integer count = jdbc.sql("SELECT COUNT(*) FROM video_asset WHERE user_id = :userId")
                .param("userId", userId)
                .query(Integer.class)
                .single();
        return count == null ? 0 : count;
    }

    public long storageBytesByUserId(long userId) {
        Long bytes = jdbc.sql("SELECT COALESCE(SUM(file_size_bytes), 0) FROM video_asset WHERE user_id = :userId")
                .param("userId", userId)
                .query(Long.class)
                .single();
        return bytes == null ? 0 : bytes;
    }

    public void markReady(long id) {
        jdbc.sql("""
                UPDATE video_asset
                SET status = 'READY', updated_at = CURRENT_TIMESTAMP, version = version + 1
                WHERE id = :id
                """)
                .param("id", id)
                .update();
    }

    public void markFailed(long id) {
        jdbc.sql("""
                UPDATE video_asset
                SET status = 'FAILED', updated_at = CURRENT_TIMESTAMP, version = version + 1
                WHERE id = :id
                """)
                .param("id", id)
                .update();
    }

    public void markProcessing(long id) {
        jdbc.sql("""
                UPDATE video_asset
                SET status = 'PROCESSING', updated_at = CURRENT_TIMESTAMP, version = version + 1
                WHERE id = :id
                """)
                .param("id", id)
                .update();
    }

    public void touchContentVersion(long id) {
        jdbc.sql("""
                UPDATE video_asset
                SET updated_at = CURRENT_TIMESTAMP, version = version + 1
                WHERE id = :id
                """)
                .param("id", id)
                .update();
    }

    private VideoAsset map(ResultSet rs, int rowNum) throws SQLException {
        return new VideoAsset(
                rs.getLong("id"),
                rs.getLong("user_id"),
                rs.getString("md5"),
                rs.getString("original_name"),
                rs.getString("storage_path"),
                rs.getLong("file_size_bytes"),
                rs.getLong("duration_ms"),
                rs.getString("status"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at")),
                rs.getInt("version")
        );
    }

    private Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
