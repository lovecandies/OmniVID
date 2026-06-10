package com.omnivid.api.job;

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
public class ProcessingJobRepository {
    private final JdbcClient jdbc;

    public ProcessingJobRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public ProcessingJob create(long videoId) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO processing_job (video_id, current_step, status, progress, started_at)
                VALUES (:videoId, 'UPLOAD_ACK', 'RUNNING', 15, CURRENT_TIMESTAMP)
                """)
                .param("videoId", videoId)
                .update(keyHolder, "id");
        return findById(keyHolder.getKey().longValue()).orElseThrow();
    }

    public ProcessingJob createRetry(long videoId, int retryCount) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO processing_job (video_id, current_step, status, progress, retry_count, started_at)
                VALUES (:videoId, 'RETRY_QUEUED', 'RUNNING', 10, :retryCount, CURRENT_TIMESTAMP)
                """)
                .param("videoId", videoId)
                .param("retryCount", retryCount)
                .update(keyHolder, "id");
        return findById(keyHolder.getKey().longValue()).orElseThrow();
    }

    public ProcessingJob createAsrReprocess(long videoId, int retryCount) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO processing_job (video_id, current_step, status, progress, retry_count, started_at)
                VALUES (:videoId, 'ASR_REPROCESS_QUEUED', 'RUNNING', 10, :retryCount, CURRENT_TIMESTAMP)
                """)
                .param("videoId", videoId)
                .param("retryCount", retryCount)
                .update(keyHolder, "id");
        return findById(keyHolder.getKey().longValue()).orElseThrow();
    }

    public Optional<ProcessingJob> findById(long id) {
        return jdbc.sql("SELECT * FROM processing_job WHERE id = :id")
                .param("id", id)
                .query(this::map)
                .optional();
    }

    public Optional<ProcessingJob> findLatestByVideoId(long videoId) {
        return jdbc.sql("""
                SELECT * FROM processing_job
                WHERE video_id = :videoId
                ORDER BY created_at DESC, id DESC
                LIMIT 1
                """)
                .param("videoId", videoId)
                .query(this::map)
                .optional();
    }

    public List<FailedJobResponse> listFailed(int limit) {
        return jdbc.sql("""
                SELECT j.id AS job_id,
                       j.video_id,
                       v.original_name,
                       j.current_step,
                       j.progress,
                       j.retry_count,
                       j.error_message,
                       j.updated_at
                FROM processing_job j
                JOIN video_asset v ON v.id = j.video_id
                WHERE j.status = 'FAILED'
                  AND j.id = (
                      SELECT latest.id
                      FROM processing_job latest
                      WHERE latest.video_id = j.video_id
                      ORDER BY latest.created_at DESC, latest.id DESC
                      LIMIT 1
                  )
                ORDER BY j.updated_at DESC, j.id DESC
                LIMIT :limit
                """)
                .param("limit", Math.max(1, Math.min(limit, 20)))
                .query(this::mapFailed)
                .list();
    }

    public boolean advance(long id, int version, String step, int progress, String status) {
        int updated = jdbc.sql("""
                UPDATE processing_job
                SET current_step = :step,
                    progress = :progress,
                    status = :status,
                    finished_at = CASE WHEN :status = 'DONE' THEN CURRENT_TIMESTAMP ELSE finished_at END,
                    updated_at = CURRENT_TIMESTAMP,
                    version = version + 1
                WHERE id = :id AND version = :version
                """)
                .param("id", id)
                .param("version", version)
                .param("step", step)
                .param("progress", progress)
                .param("status", status)
                .update();
        return updated == 1;
    }

    public boolean fail(long id, int version, String step, String errorMessage) {
        int updated = jdbc.sql("""
                UPDATE processing_job
                SET current_step = :step,
                    status = 'FAILED',
                    progress = progress,
                    error_message = :errorMessage,
                    finished_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP,
                    version = version + 1
                WHERE id = :id AND version = :version
                """)
                .param("id", id)
                .param("version", version)
                .param("step", step)
                .param("errorMessage", errorMessage)
                .update();
        return updated == 1;
    }

    private ProcessingJob map(ResultSet rs, int rowNum) throws SQLException {
        return new ProcessingJob(
                rs.getLong("id"),
                rs.getLong("video_id"),
                rs.getString("current_step"),
                rs.getString("status"),
                rs.getInt("progress"),
                rs.getInt("retry_count"),
                rs.getString("error_message"),
                toInstant(rs.getTimestamp("started_at")),
                toInstant(rs.getTimestamp("finished_at")),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at")),
                rs.getInt("version")
        );
    }

    private FailedJobResponse mapFailed(ResultSet rs, int rowNum) throws SQLException {
        return new FailedJobResponse(
                rs.getLong("job_id"),
                rs.getLong("video_id"),
                rs.getString("original_name"),
                rs.getString("current_step"),
                rs.getInt("progress"),
                rs.getInt("retry_count"),
                rs.getString("error_message"),
                toInstant(rs.getTimestamp("updated_at"))
        );
    }

    private Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
