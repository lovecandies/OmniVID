package com.omnivid.api.upload;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ChunkUploadRepository {
    private final JdbcClient jdbc;

    public ChunkUploadRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ChunkUploadSession> findReusable(
            long userId,
            String fileMd5,
            String fileName,
            long fileSize,
            long partSize,
            int totalParts
    ) {
        return jdbc.sql("""
                SELECT * FROM upload_session
                WHERE user_id = :userId
                  AND file_md5 = :fileMd5
                  AND file_name = :fileName
                  AND file_size = :fileSize
                  AND part_size = :partSize
                  AND total_parts = :totalParts
                  AND status IN ('UPLOADING', 'COMPLETING')
                ORDER BY updated_at DESC
                LIMIT 1
                """)
                .param("userId", userId)
                .param("fileMd5", fileMd5)
                .param("fileName", fileName)
                .param("fileSize", fileSize)
                .param("partSize", partSize)
                .param("totalParts", totalParts)
                .query(this::mapSession)
                .optional();
    }

    public void create(ChunkUploadSession session) {
        jdbc.sql("""
                INSERT INTO upload_session
                (id, user_id, file_name, file_size, file_md5, part_size, total_parts, uploaded_bytes, status)
                VALUES (:id, :userId, :fileName, :fileSize, :fileMd5, :partSize, :totalParts, :uploadedBytes, :status)
                """)
                .param("id", session.id())
                .param("userId", session.userId())
                .param("fileName", session.fileName())
                .param("fileSize", session.fileSize())
                .param("fileMd5", session.fileMd5())
                .param("partSize", session.partSize())
                .param("totalParts", session.totalParts())
                .param("uploadedBytes", session.uploadedBytes())
                .param("status", session.status())
                .update();
    }

    public Optional<ChunkUploadSession> findSession(String sessionId) {
        return jdbc.sql("SELECT * FROM upload_session WHERE id = :id")
                .param("id", sessionId)
                .query(this::mapSession)
                .optional();
    }

    public List<ChunkUploadPart> listParts(String sessionId) {
        return jdbc.sql("""
                SELECT * FROM upload_part
                WHERE session_id = :sessionId
                ORDER BY part_number ASC
                """)
                .param("sessionId", sessionId)
                .query(this::mapPart)
                .list();
    }

    public void upsertPart(ChunkUploadPart part) {
        try {
            jdbc.sql("""
                    INSERT INTO upload_part
                    (session_id, part_number, size_bytes, part_md5, storage_path)
                    VALUES (:sessionId, :partNumber, :sizeBytes, :partMd5, :storagePath)
                    """)
                    .param("sessionId", part.sessionId())
                    .param("partNumber", part.partNumber())
                    .param("sizeBytes", part.sizeBytes())
                    .param("partMd5", part.partMd5())
                    .param("storagePath", part.storagePath())
                    .update();
        } catch (DuplicateKeyException ignored) {
            jdbc.sql("""
                    UPDATE upload_part
                    SET size_bytes = :sizeBytes,
                        part_md5 = :partMd5,
                        storage_path = :storagePath,
                        created_at = CURRENT_TIMESTAMP
                    WHERE session_id = :sessionId AND part_number = :partNumber
                    """)
                    .param("sessionId", part.sessionId())
                    .param("partNumber", part.partNumber())
                    .param("sizeBytes", part.sizeBytes())
                    .param("partMd5", part.partMd5())
                    .param("storagePath", part.storagePath())
                    .update();
        }
        refreshUploadedBytes(part.sessionId());
    }

    public void markStatus(String sessionId, String status) {
        jdbc.sql("""
                UPDATE upload_session
                SET status = :status,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = :id
                """)
                .param("id", sessionId)
                .param("status", status)
                .update();
    }

    public void refreshUploadedBytes(String sessionId) {
        long uploadedBytes = jdbc.sql("""
                SELECT COALESCE(SUM(size_bytes), 0) FROM upload_part WHERE session_id = :sessionId
                """)
                .param("sessionId", sessionId)
                .query(Long.class)
                .single();
        jdbc.sql("""
                UPDATE upload_session
                SET uploaded_bytes = :uploadedBytes,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = :id
                """)
                .param("id", sessionId)
                .param("uploadedBytes", uploadedBytes)
                .update();
    }

    private ChunkUploadSession mapSession(ResultSet rs, int rowNum) throws SQLException {
        return new ChunkUploadSession(
                rs.getString("id"),
                rs.getLong("user_id"),
                rs.getString("file_name"),
                rs.getLong("file_size"),
                rs.getString("file_md5"),
                rs.getLong("part_size"),
                rs.getInt("total_parts"),
                rs.getLong("uploaded_bytes"),
                rs.getString("status")
        );
    }

    private ChunkUploadPart mapPart(ResultSet rs, int rowNum) throws SQLException {
        return new ChunkUploadPart(
                rs.getString("session_id"),
                rs.getInt("part_number"),
                rs.getLong("size_bytes"),
                rs.getString("part_md5"),
                rs.getString("storage_path")
        );
    }
}
