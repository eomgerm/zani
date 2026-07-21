package com.a105.zani.auth.infrastructure.jwt;

import java.time.Duration;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(
        @NotBlank @Size(min = 32) String secret,
        @NotBlank String issuer,
        @NotNull Duration accessTokenExpiration,
        @NotNull Duration refreshTokenExpiration) {

    public JwtProperties {
        requirePositive(accessTokenExpiration, "accessTokenExpiration");
        requirePositive(refreshTokenExpiration, "refreshTokenExpiration");
    }

    private static void requirePositive(Duration duration, String propertyName) {
        if (duration != null && (duration.isZero() || duration.isNegative())) {
            throw new IllegalArgumentException(propertyName + " must be positive");
        }
    }
}
