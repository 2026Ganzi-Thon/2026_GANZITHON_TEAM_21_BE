package com.ganzi.emori.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Set;

@Configuration
public class AuthWebConfig implements WebMvcConfigurer {
    private static final Set<String> PUBLIC = Set.of(
            "/api/v1/auth/login-id-availability", "/api/v1/auth/signup",
            "/api/v1/auth/login", "/api/v1/auth/refresh");
    private final AuthService auth;

    public AuthWebConfig(AuthService auth) { this.auth = auth; }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                response.setHeader("Cache-Control", "no-store");
                if ("OPTIONS".equals(request.getMethod()) || PUBLIC.contains(request.getRequestURI())) return true;
                request.setAttribute("userId", auth.authenticate(request.getHeader("Authorization")));
                return true;
            }
        }).addPathPatterns("/api/v1/**");
    }
}
