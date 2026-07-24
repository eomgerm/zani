package com.a105.zani.auth.application.googlelogin;

import com.a105.zani.auth.application.port.IssuedToken;

public record GoogleLoginResult(
        IssuedToken accessToken,
        IssuedToken refreshToken,
        String email,
        String displayName,
        String profileImageUrl,
        boolean newMember) {}
