package dev.kaiwen.eventpulse.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Authentication and user-profile business surface.
 */
public interface AuthService {

    public record TokenResponse(String accessToken, long expiresInSeconds, UserInfo user) {
    }

    public record UserInfo(UUID id, String email, String role, String displayName,
                           Long availableAmountMinor, String currency) {
    }

    public record RegisterRequest(String email, String password, String displayName, PreferencesInput preferences) {
    }

    public record PreferencesInput(List<String> categories, String coarseLocation, Integer radiusKm,
                                   Long budgetMinor) {
    }

    public record LoginRequest(String email, String password) {
    }

    public record RefreshRequest(String refreshToken) {
    }

    TokenResponse register(RegisterRequest request, HttpServletRequest httpRequest, HttpServletResponse response);

    TokenResponse login(LoginRequest request, HttpServletRequest httpRequest, HttpServletResponse response);

    TokenResponse refresh(RefreshRequest request, HttpServletRequest httpRequest, HttpServletResponse response);

    void logout(RefreshRequest request, HttpServletRequest httpRequest, HttpServletResponse response);

    /** Fresh re-authentication check for admin actions. */
    boolean verifyPassword(UUID userId, String password);

    UserInfo me(UUID userId);

    Map<String, Object> preferences(UUID userId);

    void updatePreferences(UUID userId, PreferencesInput input);
}