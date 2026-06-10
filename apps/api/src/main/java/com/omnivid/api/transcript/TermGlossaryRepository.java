package com.omnivid.api.transcript;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TermGlossaryRepository {
    private final JdbcClient jdbc;

    public TermGlossaryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<TermGlossaryEntry> list() {
        return jdbc.sql("""
                SELECT * FROM term_glossary_entry
                ORDER BY enabled DESC, updated_at DESC, id DESC
                """)
                .query(this::map)
                .list();
    }

    public List<TermGlossaryEntry> listEnabled() {
        return jdbc.sql("""
                SELECT * FROM term_glossary_entry
                WHERE enabled = TRUE
                ORDER BY updated_at DESC, id DESC
                """)
                .query(this::map)
                .list();
    }

    public TermGlossaryEntry save(String sourcePattern, String replacement) {
        try {
            jdbc.sql("""
                    INSERT INTO term_glossary_entry (source_pattern, replacement, enabled)
                    VALUES (:sourcePattern, :replacement, TRUE)
                    """)
                    .param("sourcePattern", sourcePattern)
                    .param("replacement", replacement)
                    .update();
        } catch (DuplicateKeyException ignored) {
            jdbc.sql("""
                    UPDATE term_glossary_entry
                    SET enabled = TRUE, updated_at = CURRENT_TIMESTAMP
                    WHERE source_pattern = :sourcePattern AND replacement = :replacement
                    """)
                    .param("sourcePattern", sourcePattern)
                    .param("replacement", replacement)
                    .update();
        }
        return findByNaturalKey(sourcePattern, replacement).orElseThrow();
    }

    public Optional<TermGlossaryEntry> findById(long id) {
        return jdbc.sql("SELECT * FROM term_glossary_entry WHERE id = :id")
                .param("id", id)
                .query(this::map)
                .optional();
    }

    public void setEnabled(long id, boolean enabled) {
        jdbc.sql("""
                UPDATE term_glossary_entry
                SET enabled = :enabled, updated_at = CURRENT_TIMESTAMP
                WHERE id = :id
                """)
                .param("id", id)
                .param("enabled", enabled)
                .update();
    }

    public void delete(long id) {
        jdbc.sql("DELETE FROM term_glossary_entry WHERE id = :id")
                .param("id", id)
                .update();
    }

    private Optional<TermGlossaryEntry> findByNaturalKey(String sourcePattern, String replacement) {
        return jdbc.sql("""
                SELECT * FROM term_glossary_entry
                WHERE source_pattern = :sourcePattern AND replacement = :replacement
                """)
                .param("sourcePattern", sourcePattern)
                .param("replacement", replacement)
                .query(this::map)
                .optional();
    }

    private TermGlossaryEntry map(ResultSet rs, int rowNum) throws SQLException {
        return new TermGlossaryEntry(
                rs.getLong("id"),
                rs.getString("source_pattern"),
                rs.getString("replacement"),
                rs.getBoolean("enabled")
        );
    }
}
