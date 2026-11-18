package com.ganzi.emori.auth;

import com.ganzi.emori.api.ApiException;
import com.ganzi.emori.auth.AuthModels.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class AuthController {
    private final AuthService auth;

    public AuthController(AuthService auth) { this.auth = auth; }

    @GetMapping("/auth/login-id-availability")
    public Availability availability(@RequestParam String loginId) { return auth.availability(loginId); }

    @PostMapping("/auth/signup")
    public ResponseEntity<AuthResponse> signup(@RequestBody SignupRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(auth.signup(request));
    }

    @PostMapping("/auth/login")
    public AuthResponse login(@RequestBody LoginRequest request) { return auth.login(request); }

    @PostMapping("/auth/refresh")
    public Session refresh(@RequestBody RefreshRequest request) { return auth.refresh(request); }

    @PostMapping("/auth/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@RequestHeader("Authorization") String bearer, @RequestBody Map<String, Object> body) {
        if (!body.isEmpty()) throw ApiException.invalid("로그아웃 요청 본문은 빈 객체여야 합니다.");
        auth.logout(bearer);
    }

    @GetMapping("/me")
    public Me me(HttpServletRequest request) { return auth.me((UUID) request.getAttribute("userId")); }

    @PutMapping("/me/profile")
    public Me profile(HttpServletRequest servlet, @RequestBody ProfileRequest request) {
        return auth.updateProfile((UUID) servlet.getAttribute("userId"), request);
    }

    @PutMapping("/me/birth-date")
    public Me birthDate(HttpServletRequest servlet, @RequestBody BirthDateRequest request) {
        return auth.updateBirthDate((UUID) servlet.getAttribute("userId"), request);
    }
}
