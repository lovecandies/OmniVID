package com.omnivid.api.transcript;

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
public class TranscriptVersionRepository {
    private final JdbcClient jdbc;

    public TranscriptVersionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public TranscriptVersion insert(long videoId, String source, String note, String snapshotJson) {
        int versionNo = nextVersionNo(videoId);
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO transcript_version (video_id, version_no, source, note, snapshot_json)
                VALUES (:videoId, :versionNo, :source, :note, :snapshotJson)
                """)
                .param("videoId", videoId)
                .param("versionNo", versionNo)
                .param("source", source)
                .param("note", note)
                .param("snapshotJson", snapshotJson)
                .update(keyHolder, "id");
        return findById(videoId, keyHolder.getKey().longValue()).orElseThrow();
    }

    public List<TranscriptVersion> listByVideoId(long videoId) {
        return jdbc.sql("""
                SELECT *
                FROM transcript_version
                WHERE video_id = :videoId
                ORDER BY version_no DESC
                LIMIT 20
                """)
                .param("videoId", videoId)
                .query(this::map)
                .list();
    }

    public Optional<TranscriptVersion> findById(long videoId, long id) {
        return jdbc.sql("""
                SELECT *
                FROM transcript_version
                WHERE video_id = :videoId AND id = :id
                """)
                .param("videoId", videoId)
                .param("id", id)
                .query(this::map)
                .optional();
    }

    private int nextVersionNo(long videoId) {
        Integer value = jdbc.sql("""
                SELECT COALESCE(MAX(version_no), 0) + 1
                FROM transcript_version
                WHERE video_id = :videoId
                """)
                .param("videoId", videoId)
                .query(Integer.class)
                .single();
        return value == null ? 1 : value;
    }

    private TranscriptVersion map(ResultSet rs, int rowNum) throws SQLException {
        return new TranscriptVersion(
                rs.getLong("id"),
                rs.getLong("video_id"),
                rs.getInt("version_no"),
                rs.getString("source"),
                rs.getString("note"),
                rs.getString("snapshot_json"),
                toInstant(rs.getTimestamp("created_at"))
        );
    }

    private Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
