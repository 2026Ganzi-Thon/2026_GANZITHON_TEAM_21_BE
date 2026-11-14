package com.ganzithon.backend.common;

import com.ganzithon.backend.register.RegisterService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.*;

import java.util.Arrays;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final RegisterService register;
    private final String[] origins;

    public WebConfig(
            RegisterService register,
            @Value("${emori.cors-origins}") String origins
    ) {
        this.register = register;
        this.origins = Arrays.stream(origins.split(","))
                .map(String::trim)
                .toArray(String[]::new);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(origins)
                .allowedMethods(
                        "GET", "POST", "PUT", "DELETE", "OPTIONS"
                )
                .allowedHeaders(
                        "Authorization",
                        "Content-Type",
                        "Idempotency-Key"
                )
                .maxAge(3600);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {

            @Override
            public boolean preHandle(
                    HttpServletRequest request,
                    HttpServletResponse response,
                    Object handler
            ) {
                response.setHeader("Cache-Control", "no-store");
                response.setHeader(
                        "X-Content-Type-Options",
                        "nosniff"
                );

                if ("OPTIONS".equals(request.getMethod())) {
                    return true;
                }

                String path = request.getRequestURI();

                if ("/api/v1/health".equals(path)) {
                    return true;
                }

                if ("/api/v1/register/signup".equals(path)
                        || "/api/v1/register/login".equals(path)) {
                    register.throttle("ip:" + request.getRemoteAddr());
                    return true;
                }

                String userId = register.authenticate(
                        request.getHeader("Registration")
                );

                request.setAttribute("userId", userId);

                return true;
            }

        }).addPathPatterns("/api/**");
    }
}