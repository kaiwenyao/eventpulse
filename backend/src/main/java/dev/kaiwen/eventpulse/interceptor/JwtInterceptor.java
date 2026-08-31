package dev.kaiwen.eventpulse.interceptor;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import dev.kaiwen.eventpulse.common.BaseContext;
import dev.kaiwen.eventpulse.service.JwtService;

import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class JwtInterceptor implements HandlerInterceptor {

    private final JwtService jwtService;

    public JwtInterceptor(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (isPublic(request)) {
            return true;
        }
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return unauthorized(response);
        }
        try {
            Claims claims = jwtService.parseToken(header.substring(7));
            BaseContext.setUserId(claims.get("userId", Number.class).longValue());
            BaseContext.setRole(claims.get("role", String.class));
            return true;
        }
        catch (Exception e) {
            return unauthorized(response);
        }
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
        if ("GET".equals(method) && "/api/events".equals(path)) {
            return true;
        }
        return "GET".equals(method) && path.matches("/api/events/\\d+");
    }

    private boolean unauthorized(HttpServletResponse response) throws Exception {
        response.setStatus(401);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"code\":0,\"msg\":\"未登录或 token 无效\"}");
        return false;
    }
}
