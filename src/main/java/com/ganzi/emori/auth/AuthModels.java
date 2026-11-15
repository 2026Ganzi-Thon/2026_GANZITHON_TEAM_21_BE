package com.ganzi.emori.auth;

public final class AuthModels {
    private AuthModels() {}

    public record SignupRequest(String loginId, String password, String nickname) {}
    public record LoginRequest(String loginId, String password) {}
    public record RefreshRequest(String refreshToken) {}
    public record ProfileRequest(String nickname) {}
    public record Me(String id, String loginId, String nickname, String friendCode, String adultStatus) {}
    public record Session(String accessToken, String refreshToken, long expiresIn) {}
    public record AuthResponse(Me user, Session session) {}
    public record Availability(String loginId, boolean available) {}
}
