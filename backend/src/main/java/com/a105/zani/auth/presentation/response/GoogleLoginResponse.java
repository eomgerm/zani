package com.a105.zani.auth.presentation.response;

import java.time.Instant;

import com.a105.zani.auth.application.googlelogin.GoogleLoginResult;

public record GoogleLoginResponse(
        String accessToken,
        Instant accessTokenExpiresAt,
        String email,
        String displayName,
        String profileImageUrl,
        boolean newMember) {

    public static GoogleLoginResponse from(GoogleLoginResult result) {
        return new GoogleLoginResponse(
                result.accessToken().value(),
                result.accessToken().expiresAt(),
                result.email(),
                result.displayName(),
                result.profileImageUrl(),
                result.newMember());
    }
}
