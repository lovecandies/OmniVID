package com.omnivid.api.account;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AccountTokenRepository {
    private final JdbcClient jdbc;

    public AccountTokenRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String token, long userId, String purpose, Instant expiresAt) {
        jdbc.sql("""
                INSERT INTO account_token (token, user_id, purpose, expires_at)
                VALUES (:token, :userId, :purpose, :expiresAt)
                """)
                .param("token", token)
                .param("userId", userId)
                .param("purpose", purpose)
                .param("expiresAt", Timestamp.from(expiresAt))
                .update();
    }

    public Optional<AccountToken> findUsable(String token, String purpose) {
        return jdbc.sql("""
                SELECT * FROM account_token
                WHERE token = :token
                  AND purpose = :purpose
                  AND consumed_at IS NULL
                  AND expires_at > CURRENT_TIMESTAMP
                """)
                .param("token", token)
                .param("purpose", purpose)
                .query(this::map)
                .optional();
    }

    public void consume(String token) {
        jdbc.sql("""
                UPDATE account_token
                SET consumed_at = CURRENT_TIMESTAMP
                WHERE token = :token AND consumed_at IS NULL
                """)
                .param("token", token)
                .update();
    }

    private AccountToken map(ResultSet rs, int rowNum) throws SQLException {
        return new AccountToken(
                rs.getString("token"),
                rs.getLong("user_id"),
                rs.getString("purpose"),
                toInstant(rs.getTimestamp("expires_at")),
                toInstant(rs.getTimestamp("consumed_at")),
                toInstant(rs.getTimestamp("created_at"))
        );
    }

    private Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
