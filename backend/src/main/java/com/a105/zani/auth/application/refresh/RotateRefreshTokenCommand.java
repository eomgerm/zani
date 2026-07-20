package com.a105.zani.auth.application.refresh;

import com.a105.zani.auth.application.exception.AuthErrorCode;
import com.a105.zani.common.error.BusinessException;

public record RotateRefreshTokenCommand(String refreshToken) {

    public RotateRefreshTokenCommand {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new BusinessException(AuthErrorCode.MISSING_REFRESH_TOKEN);
        }
    }
}
