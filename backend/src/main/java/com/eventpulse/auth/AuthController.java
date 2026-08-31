package com.eventpulse.auth;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.eventpulse.auth.AuthService.LoginRequest;
import com.eventpulse.auth.AuthService.RefreshRequest;
import com.eventpulse.auth.AuthService.RegisterRequest;
import com.eventpulse.auth.AuthService.TokenResponse;
import com.eventpulse.common.web.RateLimiter;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "auth")
public class AuthController {

    private final AuthService authService;
    private final RateLimiter rateLimiter;

    public AuthController(AuthService authService, RateLimiter rateLimiter) {
        this.authService = authService;
        this.rateLimiter = rateLimiter;
    }

    @Operation(summary = "Register a normal user; role/status/owner are server-assigned")
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public TokenResponse register(@Valid @RequestBody RegisterRequest request, HttpServletRequest httpRequest,
            HttpServletResponse response) {
        return authService.register(request, httpRequest, response);
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest,
            HttpServletResponse response) {
        return authService.login(request, httpRequest, response);
    }

    @PostMapping("/refresh")
    public TokenResponse refresh(@RequestBody(required = false) RefreshRequest request,
            @CookieValue(value = "ep_refresh", required = false) String cookieToken,
            HttpServletRequest httpRequest, HttpServletResponse response) {
        if (request == null && cookieToken != null) {
            request = new RefreshRequest(cookieToken);
        }
        return authService.refresh(request, httpRequest, response);
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(@RequestBody(required = false) RefreshRequest request,
            HttpServletRequest httpRequest, HttpServletResponse response) {
        authService.logout(request, httpRequest, response);
        return Map.of("ok", true);
    }

    @GetMapping("/me")
    public AuthService.UserInfo me(@AuthenticationPrincipal AuthUser user) {
        return authService.me(user.id());
    }

    @GetMapping("/me/preferences")
    public Map<String, Object> preferences(@AuthenticationPrincipal AuthUser user) {
        return authService.preferences(user.id());
    }

    @PostMapping("/me/preferences")
    public Map<String, Object> updatePreferences(@AuthenticationPrincipal AuthUser user,
            @RequestBody AuthService.PreferencesInput input) {
        authService.updatePreferences(user.id(), input);
        return authService.preferences(user.id());
    }
}
