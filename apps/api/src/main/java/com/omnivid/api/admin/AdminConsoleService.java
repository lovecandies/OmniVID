package com.omnivid.api.admin;

import com.omnivid.api.auth.UserAccount;
import com.omnivid.api.auth.UserAccountRepository;
import com.omnivid.api.common.ApiException;
import com.omnivid.api.quota.UserQuotaResponse;
import com.omnivid.api.quota.UserQuotaService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminConsoleService {
    private final UserAccountRepository users;
    private final UserQuotaService quotas;
    private final JdbcClient jdbc;

    public AdminConsoleService(UserAccountRepository users, UserQuotaService quotas, JdbcClient jdbc) {
        this.users = users;
        this.quotas = quotas;
        this.jdbc = jdbc;
    }

    public List<AdminUserSummary> users(int limit) {
        return users.list(limit).stream()
                .map(this::summary)
                .toList();
    }

    public AdminUserDetail userDetail(long userId) {
        UserAccount user = users.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found"));
        return new AdminUserDetail(summary(user), providerSummaries(userId));
    }

    public AdminResourceUsage resources() {
        return new AdminResourceUsage(
                count("SELECT COUNT(*) FROM users"),
                count("SELECT COUNT(*) FROM users WHERE disabled = FALSE AND deleted_at IS NULL"),
                count("SELECT COUNT(*) FROM video_asset"),
                longValue("SELECT COALESCE(SUM(file_size_bytes), 0) FROM video_asset"),
                count("SELECT COUNT(*) FROM knowledge_base"),
                count("SELECT COUNT(*) FROM processing_job WHERE status = 'FAILED'"),
                count("SELECT COUNT(*) FROM processing_job WHERE status = 'RUNNING'")
        );
    }

    public List<AdminTaskResponse> tasks(String status, int limit) {
        String normalizedStatus = status == null ? "" : status.trim().toUpperCase();
        if (normalizedStatus.isBlank() || "ALL".equals(normalizedStatus)) {
            return jdbc.sql(taskSql("") + " ORDER BY j.updated_at DESC, j.id DESC LIMIT :limit")
                    .param("limit", normalizeLimit(limit))
                    .query(this::mapTask)
                    .list();
        }
        return jdbc.sql(taskSql("WHERE j.status = :status") + " ORDER BY j.updated_at DESC, j.id DESC LIMIT :limit")
                .param("status", normalizedStatus)
                .param("limit", normalizeLimit(limit))
                .query(this::mapTask)
                .list();
    }

    @Transactional
    public AdminTaskResponse markFailed(long jobId, AdminTaskActionRequest request) {
        String message = request == null || request.message() == null || request.message().isBlank()
                ? "Marked failed by admin"
                : request.message().trim();
        int updated = jdbc.sql("""
                UPDATE processing_job
                SET status = 'FAILED',
                    current_step = 'ADMIN_MARKED_FAILED',
                    error_message = :message,
                    finished_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP,
                    version = version + 1
                WHERE id = :jobId
                """)
                .param("jobId", jobId)
                .param("message", message.length() > 500 ? message.substring(0, 500) : message)
                .update();
        if (updated == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Processing job not found");
        }
        return task(jobId);
    }

    private AdminTaskResponse task(long jobId) {
        return jdbc.sql(taskSql("WHERE j.id = :jobId"))
                .param("jobId", jobId)
                .query(this::mapTask)
                .optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Processing job not found"));
    }

    private AdminUserSummary summary(UserAccount user) {
        UserQuotaResponse quota = quotas.current(user.id());
        return new AdminUserSummary(
                user.id(),
                user.email(),
                user.nickname(),
                user.emailVerified(),
                user.disabled(),
                user.deletedAt(),
                quota.videoCount(),
                quota.maxVideoCount(),
                quota.storageBytes(),
                quota.maxStorageBytes(),
                quota.knowledgeBaseCount(),
                quota.maxKnowledgeBaseCount(),
                user.createdAt()
        );
    }

    private List<AdminProviderSummary> providerSummaries(long userId) {
        List<AdminProviderSummary> rows = new ArrayList<>();
        rows.addAll(jdbc.sql("""
                SELECT 'llm' AS provider_type, id, user_id, provider_name, '' AS mode, base_url, '' AS endpoint,
                       model, api_key_masked, enabled, active, last_test_status
                FROM llm_provider_config WHERE user_id = :userId
                """)
                .param("userId", userId)
                .query(this::mapProvider)
                .list());
        rows.addAll(jdbc.sql("""
                SELECT 'embedding' AS provider_type, id, user_id, provider_name, mode, base_url, '' AS endpoint,
                       model, api_key_masked, enabled, active, last_test_status
                FROM embedding_provider_config WHERE user_id = :userId
                """)
                .param("userId", userId)
                .query(this::mapProvider)
                .list());
        rows.addAll(jdbc.sql("""
                SELECT 'rerank' AS provider_type, id, user_id, provider_name, mode, base_url, endpoint,
                       model, api_key_masked, enabled, active, last_test_status
                FROM rerank_provider_config WHERE user_id = :userId
                """)
                .param("userId", userId)
                .query(this::mapProvider)
                .list());
        return rows;
    }

    private String taskSql(String whereClause) {
        return """
                SELECT j.id AS job_id,
                       j.video_id,
                       v.user_id,
                       u.email AS user_email,
                       v.original_name,
                       j.current_step,
                       j.status,
                       j.progress,
                       j.retry_count,
                       j.error_message,
                       j.updated_at
                FROM processing_job j
                JOIN video_asset v ON v.id = j.video_id
                JOIN users u ON u.id = v.user_id
                %s
                """.formatted(whereClause);
    }

    private AdminTaskResponse mapTask(ResultSet rs, int rowNum) throws SQLException {
        return new AdminTaskResponse(
                rs.getLong("job_id"),
                rs.getLong("video_id"),
                rs.getLong("user_id"),
                rs.getString("user_email"),
                rs.getString("original_name"),
                rs.getString("current_step"),
                rs.getString("status"),
                rs.getInt("progress"),
                rs.getInt("retry_count"),
                rs.getString("error_message"),
                toInstant(rs.getTimestamp("updated_at"))
        );
    }

    private AdminProviderSummary mapProvider(ResultSet rs, int rowNum) throws SQLException {
        return new AdminProviderSummary(
                rs.getString("provider_type"),
                rs.getLong("id"),
                rs.getLong("user_id"),
                rs.getString("provider_name"),
                rs.getString("mode"),
                rs.getString("base_url"),
                rs.getString("endpoint"),
                rs.getString("model"),
                rs.getString("api_key_masked"),
                rs.getBoolean("enabled"),
                rs.getBoolean("active"),
                rs.getString("last_test_status")
        );
    }

    private int normalizeLimit(int limit) {
        return Math.max(1, Math.min(limit, 200));
    }

    private int count(String sql) {
        Integer value = jdbc.sql(sql).query(Integer.class).single();
        return value == null ? 0 : value;
    }

    private long longValue(String sql) {
        Long value = jdbc.sql(sql).query(Long.class).single();
        return value == null ? 0 : value;
    }

    private Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
