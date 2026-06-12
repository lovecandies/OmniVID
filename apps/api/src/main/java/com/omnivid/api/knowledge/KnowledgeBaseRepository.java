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

    public List<KnowledgeBase> list() {
        return jdbc.sql("""
                SELECT kb.*, COUNT(kbv.video_id) AS video_count
                FROM knowledge_base kb
                LEFT JOIN knowledge_base_video kbv ON kbv.knowledge_base_id = kb.id
                GROUP BY kb.id, kb.name, kb.description, kb.created_at, kb.updated_at
                ORDER BY kb.updated_at DESC, kb.id DESC
                """)
                .query(this::mapKnowledgeBase)
                .list();
    }

    public Optional<KnowledgeBase> findById(long id) {
        return jdbc.sql("""
                SELECT kb.*, COUNT(kbv.video_id) AS video_count
                FROM knowledge_base kb
                LEFT JOIN knowledge_base_video kbv ON kbv.knowledge_base_id = kb.id
                WHERE kb.id = :id
                GROUP BY kb.id, kb.name, kb.description, kb.created_at, kb.updated_at
                """)
                .param("id", id)
                .query(this::mapKnowledgeBase)
                .optional();
    }

    public KnowledgeBase create(String name, String description) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO knowledge_base (name, description)
                VALUES (:name, :description)
                """)
                .param("name", name)
                .param("description", description)
                .update(keyHolder, "id");
        return findById(keyHolder.getKey().longValue()).orElseThrow();
    }

    public void delete(long id) {
        jdbc.sql("DELETE FROM knowledge_base_video WHERE knowledge_base_id = :id")
                .param("id", id)
                .update();
        jdbc.sql("DELETE FROM knowledge_base WHERE id = :id")
                .param("id", id)
                .update();
    }

    public void addVideo(long knowledgeBaseId, long videoId) {
        try {
            jdbc.sql("""
                    INSERT INTO knowledge_base_video (knowledge_base_id, video_id)
                    VALUES (:knowledgeBaseId, :videoId)
                    """)
                    .param("knowledgeBaseId", knowledgeBaseId)
                    .param("videoId", videoId)
                    .update();
        } catch (DuplicateKeyException ignored) {
            // Already linked; keep the operation idempotent for checkbox-style UI.
        }
        touch(knowledgeBaseId);
    }

    public void removeVideo(long knowledgeBaseId, long videoId) {
        jdbc.sql("""
                DELETE FROM knowledge_base_video
                WHERE knowledge_base_id = :knowledgeBaseId AND video_id = :videoId
                """)
                .param("knowledgeBaseId", knowledgeBaseId)
                .param("videoId", videoId)
                .update();
        touch(knowledgeBaseId);
    }

    public List<Long> videoIds(long knowledgeBaseId) {
        return jdbc.sql("""
                SELECT video_id
                FROM knowledge_base_video
                WHERE knowledge_base_id = :knowledgeBaseId
                ORDER BY created_at ASC, video_id ASC
                """)
                .param("knowledgeBaseId", knowledgeBaseId)
                .query(Long.class)
                .list();
    }

    public List<Long> knowledgeBaseIdsByVideoId(long videoId) {
        return jdbc.sql("""
                SELECT knowledge_base_id
                FROM knowledge_base_video
                WHERE video_id = :videoId
                ORDER BY knowledge_base_id ASC
                """)
                .param("videoId", videoId)
                .query(Long.class)
                .list();
    }

    public List<VideoAsset> videos(long knowledgeBaseId) {
        return jdbc.sql("""
                SELECT va.*
                FROM knowledge_base_video kbv
                JOIN video_asset va ON va.id = kbv.video_id
                WHERE kbv.knowledge_base_id = :knowledgeBaseId
                ORDER BY kbv.created_at ASC, va.id ASC
                """)
                .param("knowledgeBaseId", knowledgeBaseId)
                .query(this::mapVideo)
                .list();
    }

    private void touch(long knowledgeBaseId) {
        jdbc.sql("UPDATE knowledge_base SET updated_at = CURRENT_TIMESTAMP WHERE id = :id")
                .param("id", knowledgeBaseId)
                .update();
    }

    private KnowledgeBase mapKnowledgeBase(ResultSet rs, int rowNum) throws SQLException {
        return new KnowledgeBase(
                rs.getLong("id"),
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
