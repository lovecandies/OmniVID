package com.omnivid.api.summary;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SummaryRepository {
    private final JdbcClient jdbc;
    private final com.omnivid.api.transcript.SubtitleTextSanitizer sanitizer;

    public SummaryRepository(JdbcClient jdbc, com.omnivid.api.transcript.SubtitleTextSanitizer sanitizer) {
        this.jdbc = jdbc;
        this.sanitizer = sanitizer;
    }

    public void insert(long videoId, String type, String title, String contentJson) {
        insert(videoId, type, title, contentJson, "mock-local-agent", "v1");
    }

    public void insert(long videoId, String type, String title, String contentJson, String modelName, String promptVersion) {
        jdbc.sql("""
                INSERT INTO summary_asset (video_id, type, title, content_json, model_name, prompt_version)
                VALUES (:videoId, :type, :title, :contentJson, :modelName, :promptVersion)
                """)
                .param("videoId", videoId)
                .param("type", type)
                .param("title", title)
                .param("contentJson", contentJson)
                .param("modelName", modelName)
                .param("promptVersion", promptVersion)
                .update();
    }

    public void insertIfAbsent(long videoId, String type, String title, String contentJson) {
        insertIfAbsent(videoId, type, title, contentJson, "mock-local-agent", "v1");
    }

    public void insertIfAbsent(long videoId, String type, String title, String contentJson, String modelName, String promptVersion) {
        if (existsByVideoIdAndType(videoId, type)) {
            return;
        }
        try {
            insert(videoId, type, title, contentJson, modelName, promptVersion);
        } catch (DuplicateKeyException ignored) {
            // MySQL uk_summary_video_type is the final idempotency guard under concurrent generation.
        }
    }

    public void replaceByType(long videoId, String type, String title, String contentJson, String modelName, String promptVersion) {
        if (updateByType(videoId, type, title, contentJson, modelName, promptVersion) > 0) {
            return;
        }
        try {
            insert(videoId, type, title, contentJson, modelName, promptVersion);
        } catch (DuplicateKeyException ignored) {
            updateByType(videoId, type, title, contentJson, modelName, promptVersion);
        }
    }

    private int updateByType(long videoId, String type, String title, String contentJson, String modelName, String promptVersion) {
        return jdbc.sql("""
                UPDATE summary_asset
                SET title = :title,
                    content_json = :contentJson,
                    model_name = :modelName,
                    prompt_version = :promptVersion,
                    updated_at = CURRENT_TIMESTAMP
                WHERE video_id = :videoId AND type = :type
                """)
                .param("videoId", videoId)
                .param("type", type)
                .param("title", title)
                .param("contentJson", contentJson)
                .param("modelName", modelName)
                .param("promptVersion", promptVersion)
                .update();
    }

    public boolean existsByVideoId(long videoId) {
        Integer count = jdbc.sql("SELECT COUNT(*) FROM summary_asset WHERE video_id = :videoId")
                .param("videoId", videoId)
                .query(Integer.class)
                .single();
        return count != null && count > 0;
    }

    public boolean existsByVideoIdAndPromptVersion(long videoId, String promptVersion) {
        Integer count = jdbc.sql("""
                SELECT COUNT(*) FROM summary_asset
                WHERE video_id = :videoId AND prompt_version = :promptVersion
                """)
                .param("videoId", videoId)
                .param("promptVersion", promptVersion)
                .query(Integer.class)
                .single();
        return count != null && count > 0;
    }

    public boolean existsByVideoIdAndType(long videoId, String type) {
        Integer count = jdbc.sql("""
                SELECT COUNT(*) FROM summary_asset
                WHERE video_id = :videoId AND type = :type
                """)
                .param("videoId", videoId)
                .param("type", type)
                .query(Integer.class)
                .single();
        return count != null && count > 0;
    }

    public List<SummaryAsset> listByVideoId(long videoId) {
        return jdbc.sql("""
                SELECT * FROM summary_asset
                WHERE video_id = :videoId
                ORDER BY id ASC
                """)
                .param("videoId", videoId)
                .query(this::map)
                .list();
    }

    public int repairEncodingByVideoId(long videoId) {
        List<SummaryAsset> assets = rawListByVideoId(videoId);
        int repaired = 0;
        for (SummaryAsset asset : assets) {
            com.omnivid.api.transcript.SubtitleTextSanitizer.RepairResult title = sanitizer.repairIfBetter(asset.title());
            com.omnivid.api.transcript.SubtitleTextSanitizer.RepairResult content = sanitizer.repairIfBetter(asset.contentJson());
            if (!title.changed() && !content.changed()) {
                continue;
            }
            jdbc.sql("""
                    UPDATE summary_asset
                    SET title = :title,
                        content_json = :contentJson,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = :id
                    """)
                    .param("id", asset.id())
                    .param("title", title.text())
                    .param("contentJson", content.text())
                    .update();
            repaired++;
        }
        return repaired;
    }

    public void deleteByVideoId(long videoId) {
        jdbc.sql("DELETE FROM summary_asset WHERE video_id = :videoId")
                .param("videoId", videoId)
                .update();
    }

    private List<SummaryAsset> rawListByVideoId(long videoId) {
        return jdbc.sql("""
                SELECT * FROM summary_asset
                WHERE video_id = :videoId
                ORDER BY id ASC
                """)
                .param("videoId", videoId)
                .query(this::rawMap)
                .list();
    }

    private SummaryAsset map(ResultSet rs, int rowNum) throws SQLException {
        SummaryAsset asset = rawMap(rs, rowNum);
        return new SummaryAsset(
                asset.id(),
                asset.videoId(),
                asset.type(),
                sanitizer.repairIfBetter(asset.title()).text(),
                sanitizer.repairIfBetter(asset.contentJson()).text(),
                asset.modelName(),
                asset.promptVersion()
        );
    }

    private SummaryAsset rawMap(ResultSet rs, int rowNum) throws SQLException {
        return new SummaryAsset(
                rs.getLong("id"),
                rs.getLong("video_id"),
                rs.getString("type"),
                rs.getString("title"),
                rs.getString("content_json"),
                rs.getString("model_name"),
                rs.getString("prompt_version")
        );
    }
}
