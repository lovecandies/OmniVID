package com.omnivid.api.agent.retrieval;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class EmbeddingProviderRepository {
    private final JdbcClient jdbc;

    public EmbeddingProviderRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<EmbeddingProviderConfig> list() {
        return jdbc.sql("""
                SELECT * FROM embedding_provider_config
                ORDER BY active DESC, updated_at DESC, id DESC
                """)
                .query(this::map)
                .list();
    }

    public Optional<EmbeddingProviderConfig> findById(long id) {
        return jdbc.sql("SELECT * FROM embedding_provider_config WHERE id = :id")
                .param("id", id)
                .query(this::map)
                .optional();
    }

    public Optional<EmbeddingProviderConfig> findActive() {
        return jdbc.sql("""
                SELECT * FROM embedding_provider_config
                WHERE active = TRUE AND enabled = TRUE
                ORDER BY updated_at DESC, id DESC
                LIMIT 1
                """)
                .query(this::map)
                .optional();
    }

    public EmbeddingProviderConfig save(
            String providerName,
            String mode,
            String baseUrl,
            String model,
            String apiKeyEncoded,
            String apiKeyMasked,
            int timeoutSeconds
    ) {
        try {
            jdbc.sql("""
                    INSERT INTO embedding_provider_config
                    (provider_name, mode, base_url, model, api_key_encoded, api_key_masked, timeout_seconds, enabled, active)
                    VALUES (:providerName, :mode, :baseUrl, :model, :apiKeyEncoded, :apiKeyMasked, :timeoutSeconds, TRUE, FALSE)
                    """)
                    .param("providerName", providerName)
                    .param("mode", mode)
                    .param("baseUrl", baseUrl)
                    .param("model", model)
                    .param("apiKeyEncoded", apiKeyEncoded)
                    .param("apiKeyMasked", apiKeyMasked)
                    .param("timeoutSeconds", timeoutSeconds)
                    .update();
            return findByNaturalKey(mode, baseUrl, model).orElseThrow();
        } catch (DuplicateKeyException ignored) {
            jdbc.sql("""
                    UPDATE embedding_provider_config
                    SET provider_name = :providerName,
                        api_key_encoded = :apiKeyEncoded,
                        api_key_masked = :apiKeyMasked,
                        timeout_seconds = :timeoutSeconds,
                        enabled = TRUE,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE mode = :mode AND base_url = :baseUrl AND model = :model
                    """)
                    .param("providerName", providerName)
                    .param("mode", mode)
                    .param("baseUrl", baseUrl)
                    .param("model", model)
                    .param("apiKeyEncoded", apiKeyEncoded)
                    .param("apiKeyMasked", apiKeyMasked)
                    .param("timeoutSeconds", timeoutSeconds)
                    .update();
            return findByNaturalKey(mode, baseUrl, model).orElseThrow();
        }
    }

    public void activate(long id) {
        jdbc.sql("UPDATE embedding_provider_config SET active = FALSE, updated_at = CURRENT_TIMESTAMP WHERE active = TRUE")
                .update();
        jdbc.sql("""
                UPDATE embedding_provider_config
                SET active = TRUE, enabled = TRUE, updated_at = CURRENT_TIMESTAMP
                WHERE id = :id
                """)
                .param("id", id)
                .update();
    }

    public void updateTestResult(long id, String status, String message) {
        jdbc.sql("""
                UPDATE embedding_provider_config
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

    private Optional<EmbeddingProviderConfig> findByNaturalKey(String mode, String baseUrl, String model) {
        return jdbc.sql("""
                SELECT * FROM embedding_provider_config
                WHERE mode = :mode AND base_url = :baseUrl AND model = :model
                """)
                .param("mode", mode)
                .param("baseUrl", baseUrl)
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

    private EmbeddingProviderConfig map(ResultSet rs, int rowNum) throws SQLException {
        return new EmbeddingProviderConfig(
                rs.getLong("id"),
                rs.getString("provider_name"),
                rs.getString("mode"),
                rs.getString("base_url"),
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
