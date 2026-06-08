package com.omnivid.api.transcript;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TranscriptRepository {
    private final JdbcClient jdbc;

    public TranscriptRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insertBatch(long videoId, List<TranscriptDraft> drafts) {
        for (TranscriptDraft draft : drafts) {
            jdbc.sql("""
                    INSERT INTO transcript_segment
                    (video_id, segment_index, start_ms, end_ms, speaker, content, token_count)
                    VALUES (:videoId, :segmentIndex, :startMs, :endMs, :speaker, :content, :tokenCount)
                    """)
                    .param("videoId", videoId)
                    .param("segmentIndex", draft.segmentIndex())
                    .param("startMs", draft.startMs())
                    .param("endMs", draft.endMs())
                    .param("speaker", draft.speaker())
                    .param("content", draft.content())
                    .param("tokenCount", draft.tokenCount())
                    .update();
        }
    }

    public boolean existsByVideoId(long videoId) {
        Integer count = jdbc.sql("SELECT COUNT(*) FROM transcript_segment WHERE video_id = :videoId")
                .param("videoId", videoId)
                .query(Integer.class)
                .single();
        return count != null && count > 0;
    }

    public List<TranscriptSegment> listByVideoId(long videoId) {
        return jdbc.sql("""
                SELECT * FROM transcript_segment
                WHERE video_id = :videoId
                ORDER BY segment_index ASC
                """)
                .param("videoId", videoId)
                .query(this::map)
                .list();
    }

    public List<TranscriptSegment> search(long videoId, String keyword) {
        return jdbc.sql("""
                SELECT * FROM transcript_segment
                WHERE video_id = :videoId AND LOWER(content) LIKE LOWER(:keyword)
                ORDER BY start_ms ASC
                LIMIT 5
                """)
                .param("videoId", videoId)
                .param("keyword", "%" + keyword + "%")
                .query(this::map)
                .list();
    }

    public List<TranscriptSegment> listByVideoIds(List<Long> videoIds) {
        if (videoIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT * FROM transcript_segment
                WHERE video_id IN (:videoIds)
                ORDER BY video_id ASC, segment_index ASC
                """)
                .param("videoIds", videoIds)
                .query(this::map)
                .list();
    }

    private TranscriptSegment map(ResultSet rs, int rowNum) throws SQLException {
        return new TranscriptSegment(
                rs.getLong("id"),
                rs.getLong("video_id"),
                rs.getInt("segment_index"),
                rs.getLong("start_ms"),
                rs.getLong("end_ms"),
                rs.getString("speaker"),
                rs.getString("content"),
                rs.getInt("token_count")
        );
    }
}
