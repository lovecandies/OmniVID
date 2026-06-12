package com.omnivid.api.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class TraceFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(TraceFilter.class);

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String traceId = request.getHeader(TraceContext.TRACE_HEADER);
        long started = System.nanoTime();
        try (TraceContext.Scope ignored = TraceContext.open(traceId, Map.of(
                "method", request.getMethod(),
                "path", request.getRequestURI(),
                "status", "",
                "durationMs", ""
        ))) {
            response.setHeader(TraceContext.TRACE_HEADER, TraceContext.currentTraceId());
            try {
                filterChain.doFilter(request, response);
            } finally {
                long durationMs = (System.nanoTime() - started) / 1_000_000;
                TraceContext.put("status", response.getStatus());
                TraceContext.put("durationMs", durationMs);
                log.info("http_request_completed");
            }
        }
    }
}
