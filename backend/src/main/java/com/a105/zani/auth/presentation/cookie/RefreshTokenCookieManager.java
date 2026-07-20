package com.a105.zani.auth.presentation.cookie;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import com.a105.zani.auth.application.port.IssuedToken;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class RefreshTokenCookieManager {

    private final RefreshTokenCookieProperties properties;

    public RefreshTokenCookieManager(RefreshTokenCookieProperties properties) {
        this.properties = properties;
    }

    public Optional<String> find(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return Optional.empty();
        }

        return Arrays.stream(request.getCookies())
                .filter(cookie -> properties.refreshTokenName().equals(cookie.getName()))
                .map(Cookie::getValue)
                .filter(value -> !value.isBlank())
                .findFirst();
    }

    public void write(HttpServletResponse response, IssuedToken refreshToken) {
        Duration maxAge = Duration.between(Instant.now(), refreshToken.expiresAt());
        ResponseCookie cookie = ResponseCookie
                .from(properties.refreshTokenName(), refreshToken.value())
                .httpOnly(true)
                .secure(properties.secure())
                .sameSite(properties.sameSite())
                .path(properties.refreshTokenPath())
                .maxAge(maxAge.isNegative() ? Duration.ZERO : maxAge)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
