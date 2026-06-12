package com.omnivid.api.agent.retrieval;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class RerankProviderRepository {
    private final JdbcClient jdbc;

    public RerankProviderRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<RerankProviderConfig> list() {
        return jdbc.sql("""
                SELECT * FROM rerank_provider_config
                ORDER BY active DESC, updated_at DESC, id DESC
                """)
                .query(this::map)
                .list();
    }

    public Optional<RerankProviderConfig> findById(long id) {
        return jdbc.sql("SELECT * FROM rerank_provider_config WHERE id = :id")
                .param("id", id)
                .query(this::map)
                .optional();
    }

    public Optional<RerankProviderConfig> findActive() {
        return jdbc.sql("""
                SELECT * FROM rerank_provider_config
                WHERE active = TRUE AND enabled = TRUE
                ORDER BY updated_at DESC, id DESC
                LIMIT 1
                """)
                .query(this::map)
                .optional();
    }

    public RerankProviderConfig save(
            String providerName,
            String mode,
            String baseUrl,
            String endpoint,
            String model,
            String apiKeyEncoded,
            String apiKeyMasked,
            int timeoutSeconds
    ) {
        try {
            jdbc.sql("""
                    INSERT INTO rerank_provider_config
                    (provider_name, mode, base_url, endpoint, model, api_key_encoded, api_key_masked, timeout_seconds, enabled, active)
                    VALUES (:providerName, :mode, :baseUrl, :endpoint, :model, :apiKeyEncoded, :apiKeyMasked, :timeoutSeconds, TRUE, FALSE)
                    """)
                    .param("providerName", providerName)
                    .param("mode", mode)
                    .param("baseUrl", baseUrl)
                    .param("endpoint", endpoint)
                    .param("model", model)
                    .param("apiKeyEncoded", apiKeyEncoded)
                    .param("apiKeyMasked", apiKeyMasked)
                    .param("timeoutSeconds", timeoutSeconds)
                    .update();
            return findByNaturalKey(mode, baseUrl, endpoint, model).orElseThrow();
        } catch (DuplicateKeyException ignored) {
            jdbc.sql("""
                    UPDATE rerank_provider_config
                    SET provider_name = :providerName,
                        api_key_encoded = :apiKeyEncoded,
                        api_key_masked = :apiKeyMasked,
                        timeout_seconds = :timeoutSeconds,
                        enabled = TRUE,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE mode = :mode AND base_url = :baseUrl AND endpoint = :endpoint AND model = :model
                    """)
                    .param("providerName", providerName)
                    .param("mode", mode)
                    .param("baseUrl", baseUrl)
                    .param("endpoint", endpoint)
                    .param("model", model)
                    .param("apiKeyEncoded", apiKeyEncoded)
                    .param("apiKeyMasked", apiKeyMasked)
                    .param("timeoutSeconds", timeoutSeconds)
                    .update();
            return findByNaturalKey(mode, baseUrl, endpoint, model).orElseThrow();
        }
    }

    public void activate(long id) {
        jdbc.sql("UPDATE rerank_provider_config SET active = FALSE, updated_at = CURRENT_TIMESTAMP WHERE active = TRUE")
                .update();
        jdbc.sql("""
                UPDATE rerank_provider_config
                SET active = TRUE, enabled = TRUE, updated_at = CURRENT_TIMESTAMP
                WHERE id = :id
                """)
                .param("id", id)
                .update();
    }

    public void updateKey(long id, String apiKeyEncoded, String apiKeyMasked) {
        jdbc.sql("""
                UPDATE rerank_provider_config
                SET api_key_encoded = :apiKeyEncoded,
                    api_key_masked = :apiKeyMasked,
                    enabled = TRUE,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = :id
                """)
                .param("id", id)
                .param("apiKeyEncoded", apiKeyEncoded)
                .param("apiKeyMasked", apiKeyMasked)
                .update();
    }

    public void disable(long id) {
        jdbc.sql("""
                UPDATE rerank_provider_config
                SET enabled = FALSE,
                    active = FALSE,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = :id
                """)
                .param("id", id)
                .update();
    }

    public void delete(long id) {
        jdbc.sql("DELETE FROM rerank_provider_config WHERE id = :id")
                .param("id", id)
                .update();
    }

    public void updateTestResult(long id, String status, String message) {
        jdbc.sql("""
                UPDATE rerank_provider_config
                SET last_test_status = :status,
                    last_test_message = :message,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = :id
                """)
                .param("id", id)
                .param("status", status)
                .param("message", truncate(message))
                .update();
    }

    private Optional<RerankProviderConfig> findByNaturalKey(String mode, String baseUrl, String endpoint, String model) {
        return jdbc.sql("""
                SELECT * FROM rerank_provider_config
                WHERE mode = :mode AND base_url = :baseUrl AND endpoint = :endpoint AND model = :model
                """)
                .param("mode", mode)
                .param("baseUrl", baseUrl)
                .param("endpoint", endpoint)
                .param("model", model)
                .query(this::map)
                .optional();
    }

    private String truncate(String message) {
        if (message == null) {
            return "";
        }
        return message.length() <= 255 ? message : message.substring(0, 255);
    }

    private RerankProviderConfig map(ResultSet rs, int rowNum) throws SQLException {
        return new RerankProviderConfig(
                rs.getLong("id"),
                rs.getString("provider_name"),
                rs.getString("mode"),
                rs.getString("base_url"),
                rs.getString("endpoint"),
                rs.getString("model"),
                rs.getString("api_key_encoded"),
                rs.getString("api_key_masked"),
                rs.getInt("timeout_seconds"),
                rs.getBoolean("enabled"),
                rs.getBoolean("active"),
                rs.getString("last_test_status"),
                rs.getString("last_test_message")
        );
    }
}
