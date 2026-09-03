package dev.kaiwen.eventpulse.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import dev.kaiwen.eventpulse.common.AppProperties;
import dev.kaiwen.eventpulse.interceptor.InternalServiceInterceptor;
import dev.kaiwen.eventpulse.interceptor.JwtInterceptor;
import dev.kaiwen.eventpulse.interceptor.RequestLoggingInterceptor;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final JwtInterceptor jwtInterceptor;
    private final RequestLoggingInterceptor requestLoggingInterceptor;
    private final InternalServiceInterceptor internalServiceInterceptor;
    private final AppProperties properties;

    public WebMvcConfig(JwtInterceptor jwtInterceptor, RequestLoggingInterceptor requestLoggingInterceptor,
            InternalServiceInterceptor internalServiceInterceptor, AppProperties properties) {
        this.jwtInterceptor = jwtInterceptor;
        this.requestLoggingInterceptor = requestLoggingInterceptor;
        this.internalServiceInterceptor = internalServiceInterceptor;
        this.properties = properties;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 请求日志先于 JWT：未登录的 401 也会留下进入/结束记录。
        registry.addInterceptor(requestLoggingInterceptor).addPathPatterns("/api/**");
        registry.addInterceptor(jwtInterceptor).addPathPatterns("/api/**");
        // 服务间接口：Python AI 服务带服务凭证 + 短期用户上下文访问，
        // 不走 JWT，也不通过公网 Ingress 暴露。
        registry.addInterceptor(internalServiceInterceptor).addPathPatterns("/internal/**");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(properties.corsOriginArray())
                // PATCH 必须显式列出：经 TLS 终止代理转发时 Spring 看到的是 http，
                // 浏览器 Origin 是 https，带 Origin 的请求都会走 CORS 校验，而
                // Spring 7 对实际请求（不只预检）也校验方法，缺失即 403。
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }
}
