package com.omnivid.api.auth;

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
public class UserAccountRepository {
    private final JdbcClient jdbc;

    public UserAccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UserAccount> findByEmail(String email) {
        return jdbc.sql("SELECT * FROM users WHERE email = :email")
                .param("email", email)
                .query(this::map)
                .optional();
    }

    public Optional<UserAccount> findById(long id) {
        return jdbc.sql("SELECT * FROM users WHERE id = :id")
                .param("id", id)
                .query(this::map)
                .optional();
    }

    public UserAccount insert(String email, String passwordHash, String nickname) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO users (email, password_hash, nickname)
                VALUES (:email, :passwordHash, :nickname)
                """)
                .param("email", email)
                .param("passwordHash", passwordHash)
                .param("nickname", nickname)
                .update(keyHolder, "id");
        return findById(keyHolder.getKey().longValue()).orElseThrow();
    }

    public List<UserAccount> list(int limit) {
        return jdbc.sql("""
                SELECT * FROM users
                ORDER BY created_at DESC, id DESC
                LIMIT :limit
                """)
                .param("limit", Math.max(1, Math.min(limit, 200)))
                .query(this::map)
                .list();
    }

    public void markEmailVerified(long id) {
        jdbc.sql("""
                UPDATE users
                SET email_verified = TRUE,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = :id
                """)
                .param("id", id)
                .update();
    }

    public void updatePassword(long id, String passwordHash) {
        jdbc.sql("""
                UPDATE users
                SET password_hash = :passwordHash,
                    password_updated_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = :id
                """)
                .param("id", id)
                .param("passwordHash", passwordHash)
                .update();
    }

    public void softDelete(long id) {
        jdbc.sql("""
                UPDATE users
                SET email = :email,
                    disabled = TRUE,
                    deleted_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = :id AND deleted_at IS NULL
                """)
                .param("id", id)
                .param("email", "deleted+" + id + "+" + System.currentTimeMillis() + "@deleted.local")
                .update();
    }

    private UserAccount map(ResultSet rs, int rowNum) throws SQLException {
        return new UserAccount(
                rs.getLong("id"),
                rs.getString("email"),
                rs.getString("password_hash"),
                rs.getString("nickname"),
                rs.getBoolean("email_verified"),
                rs.getBoolean("disabled"),
                toInstant(rs.getTimestamp("deleted_at")),
                toInstant(rs.getTimestamp("password_updated_at")),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at"))
        );
    }

    private Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
