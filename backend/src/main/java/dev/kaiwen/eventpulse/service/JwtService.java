package dev.kaiwen.eventpulse.service;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;

import javax.crypto.SecretKey;

import org.springframework.stereotype.Service;

import dev.kaiwen.eventpulse.common.AppProperties;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/** 和 firmament 一样：HS256 签发 / 解析 JWT。 */
@Service
public class JwtService {

    private final AppProperties properties;

    public JwtService(AppProperties properties) {
        this.properties = properties;
    }

    public String createToken(Long userId, String role) {
        SecretKey key = Keys.hmacShaKeyFor(properties.getSecretKey().getBytes(StandardCharsets.UTF_8));
        Date exp = new Date(System.currentTimeMillis() + properties.getTokenTtlMs());
        return Jwts.builder()
                .claims(Map.of("userId", userId, "role", role))
                .expiration(exp)
                .signWith(key)
                .compact();
    }

    public Claims parseToken(String token) {
        SecretKey key = Keys.hmacShaKeyFor(properties.getSecretKey().getBytes(StandardCharsets.UTF_8));
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }

    /**
     * 解析普通登录 JWT。带 purpose 的服务间 token（{@link #createContextToken}）
     * 与登录 token 用同一密钥签名，但绝不能当登录凭证用——任何面向用户的
     * 解析都必须走本方法而不是 {@link #parseToken}，否则泄漏的上下文 token
     * （TTL 内）可以在 /api/** 上冒充对应用户的登录态。
     */
    public Claims parseLoginToken(String token) {
        Claims claims = parseToken(token);
        if (claims.get("purpose", String.class) != null) {
            throw new IllegalArgumentException("not a login token");
        }
        return claims;
    }

    /**
     * 短期「用户上下文」token：Spring Boot 调 Python AI 服务时生成，Agent 调
     * /internal/ai-tools/** 时原样带回。登录身份由服务端签名传递，模型与请求
     * 体都决定不了 userId。purpose claim 保证它不能当普通登录 JWT 用。
     */
    public String createContextToken(Long userId, String role, String requestId, long ttlSeconds) {
        SecretKey key = Keys.hmacShaKeyFor(properties.getSecretKey().getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .claims(Map.of(
                        "userId", userId,
                        "role", role,
                        "purpose", "ai-tools",
                        "requestId", requestId))
                .expiration(new Date(System.currentTimeMillis() + ttlSeconds * 1000))
                .signWith(key)
                .compact();
    }

    /** 校验 purpose=ai-tools 的上下文 token；过期、签名不符或 purpose 不对都会抛异常。 */
    public Claims parseContextToken(String token) {
        Claims claims = parseToken(token);
        if (!"ai-tools".equals(claims.get("purpose", String.class))) {
            throw new IllegalArgumentException("not an ai-tools context token");
        }
        return claims;
    }
}
