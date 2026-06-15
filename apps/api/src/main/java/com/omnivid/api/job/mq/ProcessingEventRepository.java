package com.omnivid.api.job.mq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ProcessingEventRepository {
    public static final String EVENT_TYPE = "PROCESS_VIDEO";

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public ProcessingEventRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public ProcessingEvent create(ProcessingCommand command) {
        Optional<ProcessingEvent> existing = findByJobAndType(command.jobId(), EVENT_TYPE);
        if (existing.isPresent()) {
            return existing.get();
        }
        String eventId = UUID.randomUUID().toString();
        try {
            jdbc.sql("""
                    INSERT INTO processing_event (
                        event_id, job_id, video_id, event_type, payload_json, status
                    ) VALUES (
                        :eventId, :jobId, :videoId, :eventType, :payloadJson, 'PENDING'
                    )
                    """)
                    .param("eventId", eventId)
                    .param("jobId", command.jobId())
                    .param("videoId", command.videoId())
                    .param("eventType", EVENT_TYPE)
                    .param("payloadJson", toJson(command))
                    .update();
        } catch (DuplicateKeyException exception) {
            return findByJobAndType(command.jobId(), EVENT_TYPE).orElseThrow(() -> exception);
        }
        return findById(eventId).orElseThrow();
    }

    public Optional<ProcessingEvent> findById(String eventId) {
        return jdbc.sql("SELECT * FROM processing_event WHERE event_id = :eventId")
                .param("eventId", eventId)
                .query(this::map)
                .optional();
    }

    public Optional<ProcessingEvent> findByJobAndType(long jobId, String eventType) {
        return jdbc.sql("""
                SELECT * FROM processing_event
                WHERE job_id = :jobId AND event_type = :eventType
                """)
                .param("jobId", jobId)
                .param("eventType", eventType)
                .query(this::map)
                .optional();
    }

    public List<ProcessingEvent> listDispatchable(int limit) {
        return jdbc.sql("""
                SELECT * FROM processing_event
                WHERE status IN ('PENDING', 'PUBLISH_FAILED')
                  AND (next_attempt_at IS NULL OR next_attempt_at <= CURRENT_TIMESTAMP)
                ORDER BY created_at, event_id
                LIMIT :limit
                """)
                .param("limit", clamp(limit))
                .query(this::map)
                .list();
    }

    public List<ProcessingEvent> list(long userId, String status, int limit) {
        if (status == null || status.isBlank()) {
            return jdbc.sql("""
                    SELECT pe.*
                    FROM processing_event pe
                    JOIN video_asset va ON va.id = pe.video_id
                    WHERE va.user_id = :userId
                    ORDER BY pe.updated_at DESC, pe.event_id DESC
                    LIMIT :limit
                    """)
                    .param("userId", userId)
                    .param("limit", clamp(limit))
                    .query(this::map)
                    .list();
        }
        return jdbc.sql("""
                SELECT pe.*
                FROM processing_event pe
                JOIN video_asset va ON va.id = pe.video_id
                WHERE pe.status = :status
                  AND va.user_id = :userId
                ORDER BY pe.updated_at DESC, pe.event_id DESC
                LIMIT :limit
                """)
                .param("userId", userId)
                .param("status", status.trim().toUpperCase())
                .param("limit", clamp(limit))
                .query(this::map)
                .list();
    }

    public long countByStatus(String status) {
        return jdbc.sql("SELECT COUNT(*) FROM processing_event WHERE status = :status")
                .param("status", status)
                .query(Long.class)
                .single();
    }

    public void markPublished(String eventId) {
        jdbc.sql("""
                UPDATE processing_event
                SET status = 'PUBLISHED',
                    last_error = NULL,
                    next_attempt_at = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE event_id = :eventId
                  AND status IN ('PENDING', 'PUBLISH_FAILED')
                """)
                .param("eventId", eventId)
                .update();
    }

    public void markPublishFailed(String eventId, String error) {
        int attempts = findById(eventId).map(ProcessingEvent::attemptCount).orElse(0);
        long delaySeconds = Math.min(60, Math.max(2, 1L << Math.min(6, attempts / 3)));
        jdbc.sql("""
                UPDATE processing_event
                SET status = 'PUBLISH_FAILED',
                    attempt_count = attempt_count + 1,
                    last_error = :lastError,
                    next_attempt_at = :nextAttemptAt,
                    updated_at = CURRENT_TIMESTAMP
                WHERE event_id = :eventId
                  AND status IN ('PENDING', 'PUBLISH_FAILED')
                """)
                .param("eventId", eventId)
                .param("lastError", compact(error))
                .param("nextAttemptAt", Timestamp.from(Instant.now().plusSeconds(delaySeconds)))
                .update();
    }

    public void markConsumed(String eventId) {
        updateStatus(eventId, "CONSUMED", null, false, true);
    }

    public boolean tryMarkConsuming(String eventId) {
        return jdbc.sql("""
                UPDATE processing_event
                SET status = 'CONSUMING',
                    last_error = NULL,
                    next_attempt_at = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE event_id = :eventId
                  AND status IN ('PENDING', 'PUBLISH_FAILED', 'PUBLISHED', 'CONSUME_FAILED')
                """)
                .param("eventId", eventId)
                .update() == 1;
    }

    public int recoverInterruptedConsumers() {
        return jdbc.sql("""
                UPDATE processing_event
                SET status = 'PUBLISHED',
                    last_error = 'Recovered interrupted RocketMQ consumer on application startup',
                    updated_at = CURRENT_TIMESTAMP
                WHERE status = 'CONSUMING'
                """)
                .update();
    }

    public void markConsumeFailed(String eventId, String error, boolean dlq) {
        updateStatus(eventId, dlq ? "DLQ" : "CONSUME_FAILED", compact(error), true, false);
    }

    public boolean isConsumed(String eventId) {
        return findById(eventId).map(event -> "CONSUMED".equals(event.status())).orElse(false);
    }

    public void retry(String eventId) {
        jdbc.sql("""
                UPDATE processing_event
                SET status = 'PENDING',
                    attempt_count = 0,
                    last_error = NULL,
                    next_attempt_at = NULL,
                    consumed_at = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE event_id = :eventId
                  AND status IN ('DLQ', 'PUBLISH_FAILED', 'CONSUME_FAILED')
                """)
                .param("eventId", eventId)
                .update();
    }

    public ProcessingCommand command(ProcessingEvent event) {
        try {
            return objectMapper.readValue(event.payloadJson(), ProcessingCommand.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid processing event payload", exception);
        }
    }

    private void updateStatus(String eventId, String status, String error, boolean incrementAttempt, boolean consumed) {
        jdbc.sql("""
                UPDATE processing_event
                SET status = :status,
                    attempt_count = attempt_count + :attemptDelta,
                    last_error = :lastError,
                    next_attempt_at = NULL,
                    consumed_at = CASE WHEN :consumed THEN CURRENT_TIMESTAMP ELSE consumed_at END,
                    updated_at = CURRENT_TIMESTAMP
                WHERE event_id = :eventId
                """)
                .param("eventId", eventId)
                .param("status", status)
                .param("attemptDelta", incrementAttempt ? 1 : 0)
                .param("lastError", error)
                .param("consumed", consumed)
                .update();
    }

    private String toJson(ProcessingCommand command) {
        try {
            return objectMapper.writeValueAsString(command);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize processing command", exception);
        }
    }

    private ProcessingEvent map(ResultSet rs, int rowNum) throws SQLException {
        return new ProcessingEvent(
                rs.getString("event_id"),
                rs.getLong("job_id"),
                rs.getLong("video_id"),
                rs.getString("event_type"),
                rs.getString("payload_json"),
                rs.getString("status"),
                rs.getInt("attempt_count"),
                rs.getString("last_error"),
                toInstant(rs.getTimestamp("next_attempt_at")),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at")),
                toInstant(rs.getTimestamp("consumed_at"))
        );
    }

    private int clamp(int limit) {
        return Math.max(1, Math.min(limit, 100));
    }

    private String compact(String error) {
        if (error == null || error.isBlank()) {
            return "unknown RocketMQ error";
        }
        return error.length() <= 1000 ? error : error.substring(0, 1000);
    }

    private Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
