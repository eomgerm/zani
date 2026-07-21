package com.a105.zani.auth.infrastructure.security;

import java.io.IOException;

import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import com.a105.zani.common.error.CommonErrorCode;
import com.a105.zani.common.error.handler.ApiErrorResponseWriter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class ApiAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ApiErrorResponseWriter responseWriter;

    public ApiAuthenticationEntryPoint(ApiErrorResponseWriter responseWriter) {
        this.responseWriter = responseWriter;
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authenticationException)
            throws IOException {
        responseWriter.write(request, response, CommonErrorCode.UNAUTHORIZED);
    }
}
