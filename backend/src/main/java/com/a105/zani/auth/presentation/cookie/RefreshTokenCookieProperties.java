package com.a105.zani.auth.presentation.cookie;

import java.util.Set;
import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "auth.cookie")
public record RefreshTokenCookieProperties(
        @NotBlank String refreshTokenName,
        @NotBlank String refreshTokenPath,
        boolean secure,
        @NotBlank String sameSite) {

    private static final Set<String> ALLOWED_SAME_SITE_VALUES = Set.of("Strict", "Lax", "None");

    public RefreshTokenCookieProperties {
        if (sameSite != null && !ALLOWED_SAME_SITE_VALUES.contains(sameSite)) {
            throw new IllegalArgumentException("sameSite must be Strict, Lax, or None");
        }
        if (!secure && "None".equals(sameSite)) {
            throw new IllegalArgumentException("SameSite=None requires Secure=true");
        }
    }
}
