package com.a105.zani.auth.presentation.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.a105.zani.auth.application.googlelogin.GoogleLoginCommand;
import com.a105.zani.auth.application.googlelogin.GoogleLoginResult;
import com.a105.zani.auth.application.googlelogin.GoogleLoginUseCase;
import com.a105.zani.auth.application.refresh.RotateRefreshTokenCommand;
import com.a105.zani.auth.application.refresh.RotateRefreshTokenResult;
import com.a105.zani.auth.application.refresh.RotateRefreshTokenUseCase;
import com.a105.zani.auth.presentation.cookie.RefreshTokenCookieManager;
import com.a105.zani.auth.presentation.request.GoogleLoginRequest;
import com.a105.zani.auth.presentation.response.GoogleLoginResponse;
import com.a105.zani.auth.presentation.response.RotateRefreshTokenResponse;
import com.a105.zani.common.response.ApiResponse;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final GoogleLoginUseCase googleLoginUseCase;
    private final RotateRefreshTokenUseCase rotateRefreshTokenUseCase;
    private final RefreshTokenCookieManager refreshTokenCookieManager;

    public AuthController(
            GoogleLoginUseCase googleLoginUseCase,
            RotateRefreshTokenUseCase rotateRefreshTokenUseCase,
            RefreshTokenCookieManager refreshTokenCookieManager) {
        this.googleLoginUseCase = googleLoginUseCase;
        this.rotateRefreshTokenUseCase = rotateRefreshTokenUseCase;
        this.refreshTokenCookieManager = refreshTokenCookieManager;
    }

    @PostMapping("/login/google")
    public ApiResponse<GoogleLoginResponse> loginWithGoogle(
            @Valid @RequestBody GoogleLoginRequest request, HttpServletResponse response) {
        GoogleLoginResult result = googleLoginUseCase.login(new GoogleLoginCommand(request.idToken()));
        refreshTokenCookieManager.write(response, result.refreshToken());
        return ApiResponse.success(GoogleLoginResponse.from(result));
    }

    @PostMapping("/refresh")
    public ApiResponse<RotateRefreshTokenResponse> refresh(HttpServletRequest request, HttpServletResponse response) {
        RotateRefreshTokenResult result = rotateRefreshTokenUseCase.rotate(new RotateRefreshTokenCommand(
                refreshTokenCookieManager.find(request).orElse(null)));
        refreshTokenCookieManager.write(response, result.refreshToken());
        return ApiResponse.success(RotateRefreshTokenResponse.from(result));
    }
}
