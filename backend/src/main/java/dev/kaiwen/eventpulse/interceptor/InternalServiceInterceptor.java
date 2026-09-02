package dev.kaiwen.eventpulse.interceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import dev.kaiwen.eventpulse.common.AppProperties;
import dev.kaiwen.eventpulse.common.BaseContext;
import dev.kaiwen.eventpulse.service.JwtService;

import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * /internal/** 服务间接口的认证：
 * <ol>
 *   <li>X-Internal-Token 必须等于配置的服务间凭证（常量时间比较）——浏览器拿不到，
 *       该网段也不经公网 Ingress 暴露；</li>
 *   <li>X-User-Context 是 Spring Boot 调 AI 服务时签发的短期 token，Python 调
 *       工具时原样带回。这里校验签名与 purpose 后写入 BaseContext，工具接口
 *       仍然按当前用户身份走正常的权限检查，防止伪造用户上下文。</li>
 * </ol>
 * JWT 不经过 JwtInterceptor（路径不在 /api/** 下），鉴权全部由本拦截器负责。
 */
@Component
public class InternalServiceInterceptor implements HandlerInterceptor {

    public static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";
    public static final String USER_CONTEXT_HEADER = "X-User-Context";
    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    private final AppProperties properties;
    private final JwtService jwtService;

    public InternalServiceInterceptor(AppProperties properties, JwtService jwtService) {
        this.properties = properties;
        this.jwtService = jwtService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        BaseContext.clear();
        String expected = properties.getAi().getInternalToken();
        String provided = request.getHeader(INTERNAL_TOKEN_HEADER);
        if (provided == null || !MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8))) {
            return unauthorized(response);
        }
        String contextToken = request.getHeader(USER_CONTEXT_HEADER);
        if (contextToken != null && !contextToken.isBlank()) {
            try {
                Claims claims = jwtService.parseContextToken(contextToken);
                BaseContext.setUserId(claims.get("userId", Number.class).longValue());
                BaseContext.setRole(claims.get("role", String.class));
            }
            catch (Exception e) {
                return unauthorized(response);
            }
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
            Exception ex) {
        BaseContext.clear();
    }

    private boolean unauthorized(HttpServletResponse response) throws Exception {
        response.setStatus(401);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"code\":0,\"msg\":\"Internal service authentication failed\"}");
        return false;
    }
}
