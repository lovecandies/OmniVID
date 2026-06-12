package com.omnivid.api.transcript;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TranscriptRepository {
    private final JdbcClient jdbc;
    private final SubtitleTextSanitizer sanitizer;

    public TranscriptRepository(JdbcClient jdbc, SubtitleTextSanitizer sanitizer) {
        this.jdbc = jdbc;
        this.sanitizer = sanitizer;
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
                    .param("content", sanitizer.normalizeTranscript(draft.content()))
                    .param("tokenCount", tokenCount(draft.content()))
                    .update();
        }
    }

    public void replaceByVideoId(long videoId, List<TranscriptDraft> drafts) {
        jdbc.sql("DELETE FROM transcript_segment WHERE video_id = :videoId")
                .param("videoId", videoId)
                .update();
        insertBatch(videoId, drafts);
    }

    public int repairEncodingByVideoId(long videoId) {
        List<TranscriptSegment> segments = rawListByVideoId(videoId);
        int repaired = 0;
        for (TranscriptSegment segment : segments) {
            SubtitleTextSanitizer.RepairResult result = sanitizer.repairTranscriptIfBetter(segment.content());
            if (!result.changed()) {
                continue;
            }
            jdbc.sql("""
                    UPDATE transcript_segment
                    SET content = :content, token_count = :tokenCount
                    WHERE id = :id
                    """)
                    .param("id", segment.id())
                    .param("content", result.text())
                    .param("tokenCount", tokenCount(result.text()))
                    .update();
            repaired++;
        }
        return repaired;
    }

    public int updateContentBySegmentIndex(long videoId, int segmentIndex, String content) {
        String normalized = sanitizer.normalizeTranscript(content);
        if (normalized.isBlank()) {
            return 0;
        }
        return jdbc.sql("""
                UPDATE transcript_segment
                SET content = :content, token_count = :tokenCount
                WHERE video_id = :videoId AND segment_index = :segmentIndex
                """)
                .param("videoId", videoId)
                .param("segmentIndex", segmentIndex)
                .param("content", normalized)
                .param("tokenCount", tokenCount(normalized))
                .update();
    }

    public int updateContentById(long id, String content) {
        String normalized = sanitizer.normalizeTranscript(content);
        if (normalized.isBlank()) {
            return 0;
        }
        return jdbc.sql("""
                UPDATE transcript_segment
                SET content = :content, token_count = :tokenCount
                WHERE id = :id
                """)
                .param("id", id)
                .param("content", normalized)
                .param("tokenCount", tokenCount(normalized))
                .update();
    }

    public int updateContentByVideoIdAndId(long videoId, long id, String content) {
        String normalized = sanitizer.normalizeTranscript(content);
        if (normalized.isBlank()) {
            return 0;
        }
        return jdbc.sql("""
                UPDATE transcript_segment
                SET content = :content, token_count = :tokenCount
                WHERE video_id = :videoId AND id = :id
                """)
                .param("videoId", videoId)
                .param("id", id)
                .param("content", normalized)
                .param("tokenCount", tokenCount(normalized))
                .update();
    }

    public boolean existsByVideoId(long videoId) {
        Integer count = jdbc.sql("SELECT COUNT(*) FROM transcript_segment WHERE video_id = :videoId")
                .param("videoId", videoId)
                .query(Integer.class)
                .single();
        return count != null && count > 0;
    }

    public int countByVideoId(long videoId) {
        Integer count = jdbc.sql("SELECT COUNT(*) FROM transcript_segment WHERE video_id = :videoId")
                .param("videoId", videoId)
                .query(Integer.class)
                .single();
        return count == null ? 0 : count;
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

    public Optional<TranscriptSegment> findByVideoIdAndId(long videoId, long id) {
        return jdbc.sql("""
                SELECT * FROM transcript_segment
                WHERE video_id = :videoId AND id = :id
                """)
                .param("videoId", videoId)
                .param("id", id)
                .query(this::map)
                .optional();
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

    private List<TranscriptSegment> rawListByVideoId(long videoId) {
        return jdbc.sql("""
                SELECT * FROM transcript_segment
                WHERE video_id = :videoId
                ORDER BY segment_index ASC
                """)
                .param("videoId", videoId)
                .query(this::rawMap)
                .list();
    }

    private TranscriptSegment map(ResultSet rs, int rowNum) throws SQLException {
        TranscriptSegment segment = rawMap(rs, rowNum);
        return new TranscriptSegment(
                segment.id(),
                segment.videoId(),
                segment.segmentIndex(),
                segment.startMs(),
                segment.endMs(),
                segment.speaker(),
                sanitizer.repairTranscriptIfBetter(segment.content()).text(),
                segment.tokenCount()
        );
    }

    private TranscriptSegment rawMap(ResultSet rs, int rowNum) throws SQLException {
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

    private int tokenCount(String content) {
        return Math.max(1, sanitizer.normalizeTranscript(content).length() / 2);
    }
}
