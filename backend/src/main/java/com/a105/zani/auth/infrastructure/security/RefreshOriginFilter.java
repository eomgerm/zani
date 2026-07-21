package com.a105.zani.auth.infrastructure.security;

import java.io.IOException;
import java.util.Set;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.filter.OncePerRequestFilter;

import com.a105.zani.auth.application.exception.AuthErrorCode;
import com.a105.zani.common.config.CorsProperties;
import com.a105.zani.common.error.handler.ApiErrorResponseWriter;

public class RefreshOriginFilter extends OncePerRequestFilter {

    private static final String REFRESH_PATH = "/api/v1/auth/refresh";

    private final Set<String> allowedOrigins;
    private final ApiErrorResponseWriter responseWriter;

    public RefreshOriginFilter(CorsProperties corsProperties, ApiErrorResponseWriter responseWriter) {
        this.allowedOrigins = Set.copyOf(corsProperties.allowedOrigins());
        this.responseWriter = responseWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !HttpMethod.POST.matches(request.getMethod()) || !REFRESH_PATH.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (origin == null || !allowedOrigins.contains(origin)) {
            responseWriter.write(request, response, AuthErrorCode.REFRESH_ORIGIN_FORBIDDEN);
            return;
        }

        filterChain.doFilter(request, response);
    }
}
