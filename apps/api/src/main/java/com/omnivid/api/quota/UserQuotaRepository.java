package com.omnivid.api.quota;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class UserQuotaRepository {
    private final JdbcClient jdbc;

    public UserQuotaRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UserQuota> findByUserId(long userId) {
        return jdbc.sql("SELECT * FROM user_quota WHERE user_id = :userId")
                .param("userId", userId)
                .query(this::map)
                .optional();
    }

    public void upsert(UserQuota quota) {
        try {
            jdbc.sql("""
                    INSERT INTO user_quota (user_id, max_storage_bytes, max_video_count, max_knowledge_base_count)
                    VALUES (:userId, :maxStorageBytes, :maxVideoCount, :maxKnowledgeBaseCount)
                    """)
                    .param("userId", quota.userId())
                    .param("maxStorageBytes", quota.maxStorageBytes())
                    .param("maxVideoCount", quota.maxVideoCount())
                    .param("maxKnowledgeBaseCount", quota.maxKnowledgeBaseCount())
                    .update();
        } catch (DuplicateKeyException ignored) {
            jdbc.sql("""
                    UPDATE user_quota
                    SET max_storage_bytes = :maxStorageBytes,
                        max_video_count = :maxVideoCount,
                        max_knowledge_base_count = :maxKnowledgeBaseCount,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE user_id = :userId
                    """)
                    .param("userId", quota.userId())
                    .param("maxStorageBytes", quota.maxStorageBytes())
                    .param("maxVideoCount", quota.maxVideoCount())
                    .param("maxKnowledgeBaseCount", quota.maxKnowledgeBaseCount())
                    .update();
        }
    }

    private UserQuota map(ResultSet rs, int rowNum) throws SQLException {
        return new UserQuota(
                rs.getLong("user_id"),
                rs.getLong("max_storage_bytes"),
                rs.getInt("max_video_count"),
                rs.getInt("max_knowledge_base_count")
        );
    }
}
