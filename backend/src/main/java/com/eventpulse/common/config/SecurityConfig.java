package com.eventpulse.common.config;

import java.util.List;

import com.eventpulse.common.AppProperties;
import com.eventpulse.common.error.ApiError;
import com.eventpulse.common.error.ErrorCode;
import com.eventpulse.common.web.TraceIdFilter;
import com.eventpulse.auth.TokenAuthenticationFilter;
import tools.jackson.databind.ObjectMapper;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        // Argon2id with sane memory/parallelism parameters for a single-node demo.
        return new Argon2PasswordEncoder(16, 32, 1, 19456, 2);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, TokenAuthenticationFilter tokenFilter,
            ObjectMapper mapper, AppProperties properties) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource(properties)))
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/refresh")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/events", "/api/v1/events/nearby", "/api/v1/events/*")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/recommendations", "/api/v1/meta/**").permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint((request, response, ex) -> writeJson(response, mapper, 401,
                                ErrorCode.UNAUTHENTICATED, "authentication required"))
                        .accessDeniedHandler((request, response, ex) -> writeJson(response, mapper, 403,
                                ErrorCode.FORBIDDEN, "forbidden")))
                .addFilterBefore(tokenFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private CorsConfigurationSource corsConfigurationSource(AppProperties properties) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(properties.security().corsAllowedOrigins() == null ? List.of()
                : properties.security().corsAllowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Trace-Id", "Idempotency-Key",
                "X-Reauth-Token", "If-Match"));
        config.setExposedHeaders(List.of("X-Trace-Id", "ETag"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    private static void writeJson(jakarta.servlet.http.HttpServletResponse response, ObjectMapper mapper,
            int status, ErrorCode errorCode, String message) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(mapper.writeValueAsString(
                ApiError.of(errorCode, message, java.util.Map.of(), TraceIdFilter.currentTraceId())));
    }
}
