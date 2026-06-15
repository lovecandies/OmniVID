package com.omnivid.api.knowledge;

import com.omnivid.api.video.VideoAsset;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class KnowledgeBaseRepository {
    private final JdbcClient jdbc;

    public KnowledgeBaseRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<KnowledgeBase> list(long userId) {
        return jdbc.sql("""
                SELECT kb.*, COUNT(kbv.video_id) AS video_count
                FROM knowledge_base kb
                LEFT JOIN knowledge_base_video kbv ON kbv.knowledge_base_id = kb.id
                WHERE kb.user_id = :userId
                GROUP BY kb.id, kb.user_id, kb.name, kb.description, kb.created_at, kb.updated_at
                ORDER BY kb.updated_at DESC, kb.id DESC
                """)
                .param("userId", userId)
                .query(this::mapKnowledgeBase)
                .list();
    }

    public Optional<KnowledgeBase> findById(long userId, long id) {
        return jdbc.sql("""
                SELECT kb.*, COUNT(kbv.video_id) AS video_count
                FROM knowledge_base kb
                LEFT JOIN knowledge_base_video kbv ON kbv.knowledge_base_id = kb.id
                WHERE kb.id = :id AND kb.user_id = :userId
                GROUP BY kb.id, kb.user_id, kb.name, kb.description, kb.created_at, kb.updated_at
                """)
                .param("userId", userId)
                .param("id", id)
                .query(this::mapKnowledgeBase)
                .optional();
    }

    public KnowledgeBase create(long userId, String name, String description) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO knowledge_base (user_id, name, description)
                VALUES (:userId, :name, :description)
                """)
                .param("userId", userId)
                .param("name", name)
                .param("description", description)
                .update(keyHolder, "id");
        return findById(userId, keyHolder.getKey().longValue()).orElseThrow();
    }

    public int countByUserId(long userId) {
        Integer count = jdbc.sql("SELECT COUNT(*) FROM knowledge_base WHERE user_id = :userId")
                .param("userId", userId)
                .query(Integer.class)
                .single();
        return count == null ? 0 : count;
    }

    public void delete(long userId, long id) {
        jdbc.sql("""
                DELETE FROM knowledge_base_video
                WHERE knowledge_base_id = :id
                  AND EXISTS (
                      SELECT 1 FROM knowledge_base kb
                      WHERE kb.id = :id AND kb.user_id = :userId
                  )
                """)
                .param("userId", userId)
                .param("id", id)
                .update();
        jdbc.sql("DELETE FROM knowledge_base WHERE id = :id AND user_id = :userId")
                .param("userId", userId)
                .param("id", id)
                .update();
    }

    public void addVideo(long userId, long knowledgeBaseId, long videoId) {
        try {
            jdbc.sql("""
                    INSERT INTO knowledge_base_video (knowledge_base_id, video_id)
                    SELECT :knowledgeBaseId, :videoId
                    WHERE EXISTS (
                        SELECT 1 FROM knowledge_base kb
                        WHERE kb.id = :knowledgeBaseId AND kb.user_id = :userId
                    )
                    AND EXISTS (
                        SELECT 1 FROM video_asset va
                        WHERE va.id = :videoId AND va.user_id = :userId
                    )
                    """)
                    .param("userId", userId)
                    .param("knowledgeBaseId", knowledgeBaseId)
                    .param("videoId", videoId)
                    .update();
        } catch (DuplicateKeyException ignored) {
            // Already linked; keep the operation idempotent for checkbox-style UI.
        }
        touch(userId, knowledgeBaseId);
    }

    public void removeVideo(long userId, long knowledgeBaseId, long videoId) {
        jdbc.sql("""
                DELETE FROM knowledge_base_video
                WHERE knowledge_base_id = :knowledgeBaseId AND video_id = :videoId
                  AND EXISTS (
                      SELECT 1 FROM knowledge_base kb
                      WHERE kb.id = :knowledgeBaseId AND kb.user_id = :userId
                  )
                """)
                .param("userId", userId)
                .param("knowledgeBaseId", knowledgeBaseId)
                .param("videoId", videoId)
                .update();
        touch(userId, knowledgeBaseId);
    }

    public List<Long> videoIds(long userId, long knowledgeBaseId) {
        return jdbc.sql("""
                SELECT kbv.video_id
                FROM knowledge_base_video kbv
                JOIN knowledge_base kb ON kb.id = kbv.knowledge_base_id
                WHERE kbv.knowledge_base_id = :knowledgeBaseId AND kb.user_id = :userId
                ORDER BY kbv.created_at ASC, kbv.video_id ASC
                """)
                .param("userId", userId)
                .param("knowledgeBaseId", knowledgeBaseId)
                .query(Long.class)
                .list();
    }

    public List<Long> knowledgeBaseIdsByVideoId(long userId, long videoId) {
        return jdbc.sql("""
                SELECT kbv.knowledge_base_id
                FROM knowledge_base_video kbv
                JOIN knowledge_base kb ON kb.id = kbv.knowledge_base_id
                WHERE kbv.video_id = :videoId
                  AND kb.user_id = :userId
                ORDER BY knowledge_base_id ASC
                """)
                .param("userId", userId)
                .param("videoId", videoId)
                .query(Long.class)
                .list();
    }

    public List<VideoAsset> videos(long userId, long knowledgeBaseId) {
        return jdbc.sql("""
                SELECT va.*
                FROM knowledge_base_video kbv
                JOIN knowledge_base kb ON kb.id = kbv.knowledge_base_id
                JOIN video_asset va ON va.id = kbv.video_id
                WHERE kbv.knowledge_base_id = :knowledgeBaseId
                  AND kb.user_id = :userId
                  AND va.user_id = :userId
                ORDER BY kbv.created_at ASC, va.id ASC
                """)
                .param("userId", userId)
                .param("knowledgeBaseId", knowledgeBaseId)
                .query(this::mapVideo)
                .list();
    }

    private void touch(long userId, long knowledgeBaseId) {
        jdbc.sql("UPDATE knowledge_base SET updated_at = CURRENT_TIMESTAMP WHERE id = :id AND user_id = :userId")
                .param("userId", userId)
                .param("id", knowledgeBaseId)
                .update();
    }

    private KnowledgeBase mapKnowledgeBase(ResultSet rs, int rowNum) throws SQLException {
        return new KnowledgeBase(
                rs.getLong("id"),
                rs.getLong("user_id"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getInt("video_count"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at"))
        );
    }

    private VideoAsset mapVideo(ResultSet rs, int rowNum) throws SQLException {
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
