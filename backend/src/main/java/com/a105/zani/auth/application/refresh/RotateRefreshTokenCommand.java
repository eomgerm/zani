package com.a105.zani.auth.application.refresh;

import com.a105.zani.auth.application.exception.InvalidRefreshTokenException;

public record RotateRefreshTokenCommand(String refreshToken) {

    public RotateRefreshTokenCommand {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new InvalidRefreshTokenException();
        }
    }
}
