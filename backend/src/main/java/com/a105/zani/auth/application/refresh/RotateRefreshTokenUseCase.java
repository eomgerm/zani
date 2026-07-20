package com.a105.zani.auth.application.refresh;

public interface RotateRefreshTokenUseCase {

    RotateRefreshTokenResult rotate(RotateRefreshTokenCommand command);
}
