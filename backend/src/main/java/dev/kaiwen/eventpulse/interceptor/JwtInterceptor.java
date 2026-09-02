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
        String token = header != null && header.startsWith("Bearer ")
                ? header.substring(7)
                : request.getParameter("access_token");
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
        if ("GET".equals(method) && path.startsWith("/api/events") && !path.equals("/api/events/mine")
                && !path.contains("/favourite")) {
            return true;
        }
        if ("GET".equals(method) && path.equals("/api/recommendations")) {
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
