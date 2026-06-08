package com.omnivid.api.mysql;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class MysqlExplainService {
    private final JdbcClient jdbc;

    public MysqlExplainService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public MysqlExplainResponse explain() {
        return new MysqlExplainResponse(List.of(
                explainVideoMd5(),
                explainTranscriptTimeline(),
                explainFailedRecovery()
        ));
    }

    private MysqlExplainPlan explainVideoMd5() {
        return jdbc.sql("""
                EXPLAIN SELECT * FROM video_asset WHERE md5 = :md5
                """)
                .param("md5", sampleMd5())
                .query((rs, rowNum) -> map(rs, "MD5 Dedupe", "uk_video_md5 unique index"))
                .single();
    }

    private MysqlExplainPlan explainTranscriptTimeline() {
        return jdbc.sql("""
                EXPLAIN SELECT video_id, start_ms, end_ms, segment_index
                FROM transcript_segment
                WHERE video_id = :videoId AND start_ms >= :startMs
                ORDER BY start_ms
                LIMIT 8
                """)
                .param("videoId", 1L)
                .param("startMs", 0)
                .query((rs, rowNum) -> map(rs, "Timeline Seek", "idx_transcript_video_start / covering index"))
                .single();
    }

    private MysqlExplainPlan explainFailedRecovery() {
        return jdbc.sql("""
                EXPLAIN SELECT id, video_id, current_step, retry_count, error_message
                FROM processing_job
                WHERE status = 'FAILED'
                ORDER BY updated_at DESC, id DESC
                LIMIT 8
                """)
                .query((rs, rowNum) -> map(rs, "Failed Recovery", "idx_job_status_updated(status, updated_at)"))
                .single();
    }

    private MysqlExplainPlan map(ResultSet rs, String scenario, String hook) throws SQLException {
        return new MysqlExplainPlan(
                scenario,
                hook,
                rs.getString("table"),
                rs.getString("type"),
                rs.getString("possible_keys"),
                rs.getString("key"),
                rs.getLong("rows"),
                rs.getString("filtered"),
                rs.getString("Extra")
        );
    }

    private String sampleMd5() {
        return jdbc.sql("SELECT md5 FROM video_asset ORDER BY id LIMIT 1")
                .query(String.class)
                .optional()
                .orElse("00000000000000000000000000000000");
    }
}
