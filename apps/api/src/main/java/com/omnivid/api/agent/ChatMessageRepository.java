package com.omnivid.api.agent;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ChatMessageRepository {
    private final JdbcClient jdbc;

    public ChatMessageRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long videoId, String role, String content, String citation) {
        jdbc.sql("""
                INSERT INTO chat_message (video_id, role, content, citation)
                VALUES (:videoId, :role, :content, :citation)
                """)
                .param("videoId", videoId)
                .param("role", role)
                .param("content", content)
                .param("citation", citation)
                .update();
    }

    public List<ChatMessage> listRecentByVideoId(long videoId, int limit) {
        return jdbc.sql("""
                SELECT * FROM (
                    SELECT *
                    FROM chat_message
                    WHERE video_id = :videoId
                    ORDER BY id DESC
                    LIMIT :limit
                ) recent_messages
                ORDER BY id ASC
                """)
                .param("videoId", videoId)
                .param("limit", Math.max(1, Math.min(limit, 50)))
                .query(this::map)
                .list();
    }

    public int deleteByVideoId(long videoId) {
        return jdbc.sql("DELETE FROM chat_message WHERE video_id = :videoId")
                .param("videoId", videoId)
                .update();
    }

    private ChatMessage map(ResultSet rs, int rowNum) throws SQLException {
        return new ChatMessage(
                rs.getLong("id"),
                rs.getLong("video_id"),
                rs.getString("role"),
                rs.getString("content"),
                rs.getString("citation"),
                rs.getTimestamp("created_at").toInstant()
        );
    }
}
