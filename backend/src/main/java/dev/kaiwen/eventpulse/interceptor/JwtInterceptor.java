package dev.kaiwen.eventpulse.interceptor;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.AsyncHandlerInterceptor;

import dev.kaiwen.eventpulse.common.BaseContext;
import dev.kaiwen.eventpulse.service.JwtService;

import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * JWT 拦截器。实现 AsyncHandlerInterceptor：SSE 是异步请求，建立连接后
 * 原请求线程先回到线程池，{@link #afterConcurrentHandlingStarted} 保证此时
 * 立即清掉 ThreadLocal，用户上下文不会泄漏给复用该线程的下一个请求。
 */
@Component
public class JwtInterceptor implements AsyncHandlerInterceptor {

    private final JwtService jwtService;

    public JwtInterceptor(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        // 先清理可能残留的旧上下文（线程复用），再解析当前请求的 JWT。
        BaseContext.clear();
        if (isPublic(request)) {
            return true;
        }
        String header = request.getHeader("Authorization");
        String token = header != null && header.startsWith("Bearer ")
                ? header.substring(7)
                : null;
        if (token == null || token.isBlank()) {
            return unauthorized(response);
        }
        try {
            Claims claims = jwtService.parseToken(token);
            BaseContext.setUserId(claims.get("userId", Number.class).longValue());
            BaseContext.setRole(claims.get("role", String.class));
            return true;
        }
        catch (Exception e) {
            return unauthorized(response);
        }
    }

    /**
     * 异步请求（SSE）开始并发处理时调用：此时请求线程要回线程池，
     * 必须立刻清掉用户上下文，不能等异步处理结束。
     */
    @Override
    public void afterConcurrentHandlingStarted(HttpServletRequest request, HttpServletResponse response,
            Object handler) {
        BaseContext.clear();
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
            Exception ex) {
        BaseContext.clear();
    }

    public static boolean isPublic(HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getRequestURI();
        if ("OPTIONS".equalsIgnoreCase(method)) {
            return true;
        }
        if ("POST".equals(method) && ("/api/auth/login".equals(path) || "/api/auth/register".equals(path))) {
            return true;
        }
        // AI 找活动助手是公开路径：游客可单轮提问；带 Bearer 时由 AiGateway
        // 自行解析用户身份并加载其会话。
        if ("POST".equals(method) && "/api/ai/discovery/chat".equals(path)) {
            return true;
        }
        if ("GET".equals(method) && path.startsWith("/api/events") && !path.equals("/api/events/mine")
                && !path.contains("/favourite")) {
            return true;
        }
        return "GET".equals(method) && path.startsWith("/api/media/images/");
    }

    private boolean unauthorized(HttpServletResponse response) throws Exception {
        response.setStatus(401);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"code\":0,\"msg\":\"Not signed in or token invalid\"}");
        return false;
    }
}
