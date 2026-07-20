package com.a105.zani.auth.presentation.response;

import java.time.Instant;

import com.a105.zani.auth.application.refresh.RotateRefreshTokenResult;

public record RotateRefreshTokenResponse(
        String accessToken,
        Instant accessTokenExpiresAt) {

    public static RotateRefreshTokenResponse from(RotateRefreshTokenResult result) {
        return new RotateRefreshTokenResponse(
                result.accessToken().value(),
                result.accessToken().expiresAt());
    }
}
