package com.omnivid.api.redis;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class RedisInspectService {
    private final ObjectProvider<StringRedisTemplate> redis;

    public RedisInspectService(ObjectProvider<StringRedisTemplate> redis) {
        this.redis = redis;
    }

    public RedisInspectResponse inspect() {
        StringRedisTemplate template = redis.getIfAvailable();
        if (template == null || template.getConnectionFactory() == null) {
            return new RedisInspectResponse(false, fallbackItems());
        }

        try (RedisConnection connection = template.getConnectionFactory().getConnection()) {
            boolean connected = "PONG".equalsIgnoreCase(connection.ping());
            return new RedisInspectResponse(connected, List.of(
                    inspect(connection, "SETNX Dedupe Lock", "video:lock:*", "SETNX + ttl 防重复提交"),
                    inspect(connection, "Progress Cache", "omnivid:progress:*", "SSE 进度断线补偿"),
                    inspect(connection, "Agent Rate Limit", "omnivid:agent:rate:*", "固定窗口限流，可演进 Lua 令牌桶"),
                    inspect(connection, "Semantic Cache", "omnivid:agent:semantic:*", "LLM 答案缓存，降低重复推理成本"),
                    inspect(connection, "Short Memory", "omnivid:agent:memory:last-question:*", "多轮追问短期记忆")
            ));
        } catch (Exception exception) {
            return new RedisInspectResponse(false, fallbackItems());
        }
    }

    private RedisKeyInspectItem inspect(RedisConnection connection, String hook, String pattern, String note) {
        String sampleKey = sampleKey(connection, pattern);
        if (sampleKey.isBlank()) {
            return new RedisKeyInspectItem(hook, pattern, "", "", -2, false, note);
        }
        byte[] keyBytes = sampleKey.getBytes(StandardCharsets.UTF_8);
        Long ttl = connection.keyCommands().ttl(keyBytes);
        org.springframework.data.redis.connection.DataType type = connection.keyCommands().type(keyBytes);
        return new RedisKeyInspectItem(
                hook,
                pattern,
                sampleKey,
                type == null ? "" : type.code(),
                ttl == null ? -2 : ttl,
                true,
                note
        );
    }

    private String sampleKey(RedisConnection connection, String pattern) {
        ScanOptions options = ScanOptions.scanOptions().match(pattern).count(20).build();
        try (Cursor<byte[]> cursor = connection.keyCommands().scan(options)) {
            if (cursor.hasNext()) {
                return new String(cursor.next(), StandardCharsets.UTF_8);
            }
            return "";
        }
    }

    private List<RedisKeyInspectItem> fallbackItems() {
        return List.of(
                new RedisKeyInspectItem("SETNX Dedupe Lock", "video:lock:*", "", "", -2, false, "SETNX + ttl 防重复提交"),
                new RedisKeyInspectItem("Progress Cache", "omnivid:progress:*", "", "", -2, false, "SSE 进度断线补偿"),
                new RedisKeyInspectItem("Agent Rate Limit", "omnivid:agent:rate:*", "", "", -2, false, "固定窗口限流，可演进 Lua 令牌桶"),
                new RedisKeyInspectItem("Semantic Cache", "omnivid:agent:semantic:*", "", "", -2, false, "LLM 答案缓存，降低重复推理成本"),
                new RedisKeyInspectItem("Short Memory", "omnivid:agent:memory:last-question:*", "", "", -2, false, "多轮追问短期记忆")
        );
    }
}
