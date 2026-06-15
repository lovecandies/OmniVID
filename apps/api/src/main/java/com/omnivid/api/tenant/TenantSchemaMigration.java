package com.omnivid.api.tenant;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TenantSchemaMigration implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(TenantSchemaMigration.class);

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;

    public TenantSchemaMigration(DataSource dataSource, JdbcTemplate jdbc) {
        this.dataSource = dataSource;
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            String product = metaData.getDatabaseProductName().toLowerCase();
            boolean mysql = product.contains("mysql");
            ensureAccountColumns(metaData);
            ensureColumn(metaData, "video_asset", "file_size_bytes", "BIGINT NOT NULL DEFAULT 0");
            ensureTenantColumn(metaData, "knowledge_base");
            ensureTenantColumn(metaData, "llm_provider_config");
            ensureTenantColumn(metaData, "embedding_provider_config");
            ensureTenantColumn(metaData, "rerank_provider_config");
            migrateUniqueIndexes(metaData, mysql);
        }
    }

    private void ensureAccountColumns(DatabaseMetaData metaData) throws SQLException {
        ensureColumn(metaData, "users", "email_verified", "BOOLEAN NOT NULL DEFAULT FALSE");
        ensureColumn(metaData, "users", "disabled", "BOOLEAN NOT NULL DEFAULT FALSE");
        ensureColumn(metaData, "users", "deleted_at", "TIMESTAMP");
        ensureColumn(metaData, "users", "password_updated_at", "TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP");
    }

    private void ensureTenantColumn(DatabaseMetaData metaData, String tableName) throws SQLException {
        if (!tableExists(metaData, tableName) || columnExists(metaData, tableName, "user_id")) {
            return;
        }
        execute("ALTER TABLE " + tableName + " ADD COLUMN user_id BIGINT NOT NULL DEFAULT 1");
        log.info("tenant_column_added table={}", tableName);
    }

    private void ensureColumn(DatabaseMetaData metaData, String tableName, String columnName, String definition) throws SQLException {
        if (!tableExists(metaData, tableName) || columnExists(metaData, tableName, columnName)) {
            return;
        }
        execute("ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + definition);
        log.info("schema_column_added table={} column={}", tableName, columnName);
    }

    private void migrateUniqueIndexes(DatabaseMetaData metaData, boolean mysql) throws SQLException {
        dropIndexIfExists(metaData, mysql, "video_asset", "uk_video_md5");
        createUniqueIndexIfMissing(metaData, "video_asset", "uk_video_user_md5", "user_id, md5");

        dropIndexIfExists(metaData, mysql, "knowledge_base", "uk_knowledge_base_name");
        createUniqueIndexIfMissing(metaData, "knowledge_base", "uk_knowledge_base_user_name", "user_id, name");

        dropIndexIfExists(metaData, mysql, "llm_provider_config", "uk_llm_provider");
        createUniqueIndexIfMissing(metaData, "llm_provider_config", "uk_llm_provider_user", "user_id, provider_name, base_url, model");

        dropIndexIfExists(metaData, mysql, "embedding_provider_config", "uk_embedding_provider");
        createUniqueIndexIfMissing(metaData, "embedding_provider_config", "uk_embedding_provider_user", "user_id, mode, base_url, model");

        dropIndexIfExists(metaData, mysql, "rerank_provider_config", "uk_rerank_provider");
        createUniqueIndexIfMissing(metaData, "rerank_provider_config", "uk_rerank_provider_user", "user_id, mode, base_url, endpoint, model");
    }

    private void dropIndexIfExists(DatabaseMetaData metaData, boolean mysql, String tableName, String indexName) throws SQLException {
        if (!tableExists(metaData, tableName) || !indexExists(metaData, tableName, indexName)) {
            return;
        }
        if (mysql) {
            execute("ALTER TABLE " + tableName + " DROP INDEX " + indexName);
        } else {
            execute("DROP INDEX IF EXISTS " + indexName);
        }
        log.info("tenant_legacy_index_dropped table={} index={}", tableName, indexName);
    }

    private void createUniqueIndexIfMissing(
            DatabaseMetaData metaData,
            String tableName,
            String indexName,
            String columns
    ) throws SQLException {
        if (!tableExists(metaData, tableName) || indexExists(metaData, tableName, indexName)) {
            return;
        }
        execute("CREATE UNIQUE INDEX " + indexName + " ON " + tableName + " (" + columns + ")");
        log.info("tenant_unique_index_created table={} index={}", tableName, indexName);
    }

    private boolean tableExists(DatabaseMetaData metaData, String tableName) throws SQLException {
        try (ResultSet tables = metaData.getTables(null, null, tableName, null)) {
            if (tables.next()) {
                return true;
            }
        }
        try (ResultSet tables = metaData.getTables(null, null, tableName.toUpperCase(), null)) {
            return tables.next();
        }
    }

    private boolean columnExists(DatabaseMetaData metaData, String tableName, String columnName) throws SQLException {
        try (ResultSet columns = metaData.getColumns(null, null, tableName, columnName)) {
            if (columns.next()) {
                return true;
            }
        }
        try (ResultSet columns = metaData.getColumns(null, null, tableName.toUpperCase(), columnName.toUpperCase())) {
            return columns.next();
        }
    }

    private boolean indexExists(DatabaseMetaData metaData, String tableName, String indexName) throws SQLException {
        try (ResultSet indexes = metaData.getIndexInfo(null, null, tableName, false, false)) {
            if (containsIndex(indexes, indexName)) {
                return true;
            }
        }
        try (ResultSet indexes = metaData.getIndexInfo(null, null, tableName.toUpperCase(), false, false)) {
            return containsIndex(indexes, indexName);
        }
    }

    private boolean containsIndex(ResultSet indexes, String indexName) throws SQLException {
        while (indexes.next()) {
            String current = indexes.getString("INDEX_NAME");
            if (current != null && current.equalsIgnoreCase(indexName)) {
                return true;
            }
        }
        return false;
    }

    private void execute(String sql) {
        try {
            jdbc.execute(sql);
        } catch (RuntimeException exception) {
            log.warn("tenant_schema_migration_sql_failed sql={} error={}", sql, exception.getMessage());
        }
    }
}
