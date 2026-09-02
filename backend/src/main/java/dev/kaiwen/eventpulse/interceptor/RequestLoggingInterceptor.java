package dev.kaiwen.eventpulse.interceptor;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.AsyncHandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 请求过程日志：进入时记下 method/URI，结束时记下 status 与耗时。
 * 通过 MDC {@code requestId} 把同一请求里的 Hibernate SQL 日志串起来。
 * 实现 {@link AsyncHandlerInterceptor}：SSE 异步开始后立刻清掉 MDC，避免线程复用串号。
 */
@Component
public class RequestLoggingInterceptor implements AsyncHandlerInterceptor {

    static final String REQUEST_ID = "requestId";
    static final String START_NS = RequestLoggingInterceptor.class.getName() + ".startNs";

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingInterceptor.class);

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String requestId = UUID.randomUUID().toString();
        MDC.put(REQUEST_ID, requestId);
        request.setAttribute(START_NS, System.nanoTime());
        log.info("request started {} {}{}", request.getMethod(), request.getRequestURI(), querySuffix(request));
        return true;
    }

    @Override
    public void afterConcurrentHandlingStarted(HttpServletRequest request, HttpServletResponse response,
            Object handler) {
        MDC.remove(REQUEST_ID);
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
            Exception ex) {
        try {
            long durationMs = durationMs(request);
            if (ex != null) {
                log.warn("request failed {} {}{} status={} durationMs={} error={}",
                        request.getMethod(), request.getRequestURI(), querySuffix(request),
                        response.getStatus(), durationMs, ex.toString());
                return;
            }
            log.info("request completed {} {}{} status={} durationMs={}",
                    request.getMethod(), request.getRequestURI(), querySuffix(request),
                    response.getStatus(), durationMs);
        }
        finally {
            MDC.remove(REQUEST_ID);
        }
    }

    private static String querySuffix(HttpServletRequest request) {
        String query = request.getQueryString();
        return query == null || query.isBlank() ? "" : "?" + query;
    }

    private static long durationMs(HttpServletRequest request) {
        Object start = request.getAttribute(START_NS);
        if (!(start instanceof Long startNs)) {
            return -1L;
        }
        return (System.nanoTime() - startNs) / 1_000_000L;
    }
}
