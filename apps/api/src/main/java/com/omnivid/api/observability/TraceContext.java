package com.omnivid.api.observability;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;

public final class TraceContext {
    public static final String TRACE_ID = "traceId";
    public static final String TRACE_HEADER = "X-Trace-Id";

    private TraceContext() {
    }

    public static String currentTraceId() {
        return MDC.get(TRACE_ID);
    }

    public static String currentOrNew() {
        String current = currentTraceId();
        return hasText(current) ? current : newTraceId();
    }

    public static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public static Scope open(String traceId, Map<String, ?> values) {
        Map<String, String> previous = new LinkedHashMap<>();
        put(previous, TRACE_ID, hasText(traceId) ? traceId : newTraceId());
        values.forEach((key, value) -> put(previous, key, value == null ? "" : String.valueOf(value)));
        return new Scope(previous);
    }

    public static void put(String key, Object value) {
        if (value == null) {
            MDC.remove(key);
            return;
        }
        MDC.put(key, String.valueOf(value));
    }

    private static void put(Map<String, String> previous, String key, String value) {
        previous.put(key, MDC.get(key));
        if (hasText(value)) {
            MDC.put(key, value);
        } else {
            MDC.remove(key);
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public static final class Scope implements AutoCloseable {
        private final Map<String, String> previous;

        private Scope(Map<String, String> previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            previous.forEach((key, value) -> {
                if (value == null) {
                    MDC.remove(key);
                } else {
                    MDC.put(key, value);
                }
            });
        }
    }
}
