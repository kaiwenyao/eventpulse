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
 * 实现 {@link AsyncHandlerInterceptor}：SSE 异步开始后立刻清掉 MDC，避免线程复用串号；
 * 异步结束时的 ASYNC 再分派会重跑 preHandle，届时沿用首进的 requestId 与起始时间。
 */
@Component
public class RequestLoggingInterceptor implements AsyncHandlerInterceptor {

    static final String REQUEST_ID = "requestId";
    static final String START_NS = RequestLoggingInterceptor.class.getName() + ".startNs";

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingInterceptor.class);

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (request.getAttribute(START_NS) instanceof Long) {
            // SSE 异步再分派会重跑 preHandle：沿用首进的 requestId 与起始时间，
            // 不重复打 started，durationMs 才是整段连接的耗时。
            // attribute 不会被 afterConcurrentHandlingStarted 清掉（它只清 MDC）。
            if (request.getAttribute(REQUEST_ID) instanceof String requestId) {
                MDC.put(REQUEST_ID, requestId);
            }
            return true;
        }
        String requestId = UUID.randomUUID().toString();
        request.setAttribute(REQUEST_ID, requestId);
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
            // 异常被 @ExceptionHandler 接住后，afterCompletion 拿到的 ex 是 null，
            // 所以失败与否以响应状态为准：5xx 打 WARN，4xx 属正常业务流量保持 INFO。
            if (response.getStatus() >= 500 || ex != null) {
                log.warn("request failed {} {}{} status={} durationMs={} error={}",
                        request.getMethod(), request.getRequestURI(), querySuffix(request),
                        response.getStatus(), durationMs, ex == null ? "-" : ex.toString());
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
