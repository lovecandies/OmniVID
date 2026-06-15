package com.omnivid.api.account;

import com.omnivid.api.auth.UserAccount;
import com.omnivid.api.quota.UserQuotaResponse;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AccountDataExportService {
    private final JdbcTemplate jdbc;

    public AccountDataExportService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> export(UserAccount user, UserQuotaResponse quota) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("exportedAt", Instant.now().toString());
        data.put("user", Map.of(
                "id", user.id(),
                "email", user.email(),
                "nickname", user.nickname(),
                "emailVerified", user.emailVerified(),
                "createdAt", user.createdAt()
        ));
        data.put("quota", quota);
        data.put("videos", query("""
                SELECT id, md5, original_name, file_size_bytes, duration_ms, status, created_at, updated_at
                FROM video_asset
                WHERE user_id = ?
                ORDER BY id
                """, user.id()));
        data.put("jobs", query("""
                SELECT j.id, j.video_id, j.current_step, j.status, j.progress, j.retry_count, j.error_message, j.created_at, j.updated_at
                FROM processing_job j
                JOIN video_asset v ON v.id = j.video_id
                WHERE v.user_id = ?
                ORDER BY j.id
                """, user.id()));
        data.put("transcripts", query("""
                SELECT t.video_id, t.segment_index, t.start_ms, t.end_ms, t.speaker, t.content
                FROM transcript_segment t
                JOIN video_asset v ON v.id = t.video_id
                WHERE v.user_id = ?
                ORDER BY t.video_id, t.segment_index
                """, user.id()));
        data.put("summaries", query("""
                SELECT s.video_id, s.type, s.title, s.model_name, s.prompt_version, s.created_at
                FROM summary_asset s
                JOIN video_asset v ON v.id = s.video_id
                WHERE v.user_id = ?
                ORDER BY s.video_id, s.type
                """, user.id()));
        data.put("knowledgeBases", query("""
                SELECT id, name, description, created_at, updated_at
                FROM knowledge_base
                WHERE user_id = ?
                ORDER BY id
                """, user.id()));
        data.put("chatMessages", query("""
                SELECT c.video_id, c.role, c.content, c.citation, c.created_at
                FROM chat_message c
                JOIN video_asset v ON v.id = c.video_id
                WHERE v.user_id = ?
                ORDER BY c.id
                """, user.id()));
        data.put("providers", Map.of(
                "llm", queryMaskedProviders("llm_provider_config", user.id()),
                "embedding", queryMaskedProviders("embedding_provider_config", user.id()),
                "rerank", queryMaskedProviders("rerank_provider_config", user.id())
        ));
        return data;
    }

    private List<Map<String, Object>> query(String sql, long userId) {
        return jdbc.queryForList(sql, userId);
    }

    private List<Map<String, Object>> queryMaskedProviders(String table, long userId) {
        return jdbc.queryForList("""
                SELECT id, provider_name, base_url, model, api_key_masked, enabled, active, last_test_status, updated_at
                FROM %s
                WHERE user_id = ?
                ORDER BY id
                """.formatted(table), userId);
    }
}
