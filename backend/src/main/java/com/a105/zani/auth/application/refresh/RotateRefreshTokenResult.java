package com.a105.zani.auth.application.refresh;

import com.a105.zani.auth.application.port.IssuedToken;

public record RotateRefreshTokenResult(IssuedToken accessToken, IssuedToken refreshToken) {}
