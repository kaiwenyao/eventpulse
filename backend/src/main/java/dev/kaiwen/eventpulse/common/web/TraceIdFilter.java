package dev.kaiwen.eventpulse.common.web;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Establishes a per-request trace id (honours incoming X-Trace-Id), exposes it
 * to logs via MDC and echoes it back so errors can be correlated.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Trace-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = request.getHeader(HEADER);
        if (traceId == null || traceId.isBlank() || traceId.length() > 64) {
            traceId = UUID.randomUUID().toString();
        }
        MDC.put("traceId", traceId);
        MDC.put("module", moduleOf(request));
        try {
            response.setHeader(HEADER, traceId);
            chain.doFilter(request, response);
        }
        finally {
            MDC.remove("traceId");
            MDC.remove("module");
        }
    }

    private static String moduleOf(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri.contains("/api/v1/bookings") || uri.contains("/api/v1/organiser/tickets")) {
            return "booking";
        }
        if (uri.contains("/api/v1/auth")) {
            return "auth";
        }
        if (uri.contains("/api/v1/events") || uri.contains("/api/v1/recommendations")) {
            return "catalogue";
        }
        return "app";
    }

    public static String currentTraceId() {
        return MDC.get("traceId");
    }
}
