package com.a105.zani.auth.presentation.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.a105.zani.auth.application.exception.InvalidRefreshTokenException;
import com.a105.zani.auth.application.googlelogin.GoogleLoginCommand;
import com.a105.zani.auth.application.googlelogin.GoogleLoginResult;
import com.a105.zani.auth.application.googlelogin.GoogleLoginUseCase;
import com.a105.zani.auth.application.logout.LogoutCommand;
import com.a105.zani.auth.application.logout.LogoutUseCase;
import com.a105.zani.auth.application.refresh.RotateRefreshTokenCommand;
import com.a105.zani.auth.application.refresh.RotateRefreshTokenResult;
import com.a105.zani.auth.application.refresh.RotateRefreshTokenUseCase;
import com.a105.zani.auth.presentation.cookie.RefreshTokenCookieManager;
import com.a105.zani.auth.presentation.request.GoogleLoginRequest;
import com.a105.zani.auth.presentation.response.GoogleLoginResponse;
import com.a105.zani.auth.presentation.response.RotateRefreshTokenResponse;
import com.a105.zani.common.response.ApiResponse;

@Tag(name = "인증", description = "Google 로그인과 토큰 재발급. 다른 API 는 모두 여기서 받은 Access Token 이 있어야 호출할 수 있습니다.")
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final GoogleLoginUseCase googleLoginUseCase;
    private final RotateRefreshTokenUseCase rotateRefreshTokenUseCase;
    private final LogoutUseCase logoutUseCase;
    private final RefreshTokenCookieManager refreshTokenCookieManager;

    public AuthController(
            GoogleLoginUseCase googleLoginUseCase,
            RotateRefreshTokenUseCase rotateRefreshTokenUseCase,
            LogoutUseCase logoutUseCase,
            RefreshTokenCookieManager refreshTokenCookieManager) {
        this.googleLoginUseCase = googleLoginUseCase;
        this.rotateRefreshTokenUseCase = rotateRefreshTokenUseCase;
        this.logoutUseCase = logoutUseCase;
        this.refreshTokenCookieManager = refreshTokenCookieManager;
    }

    @Operation(summary = "Google 로그인", description = """
                    프론트엔드가 Google 로그인 버튼에서 받은 **ID Token** 을 그대로 보내면, 서버가 Google 에 검증한 뒤 ZANI 계정을 만들거나 찾아 로그인시킵니다.

                    - 처음 로그인하는 사용자는 이때 회원으로 등록됩니다(별도 회원가입 API 없음).
                    - 응답의 **Access Token** 은 이후 요청의 `Authorization: Bearer ...` 헤더에 넣습니다. 유효기간 1시간.
                    - **Refresh Token** 은 본문이 아니라 `refresh_token` **HttpOnly 쿠키**로 내려갑니다. 자바스크립트로 읽을 수 없으니 그대로 두고, 재발급 때 요청에 쿠키만 함께 보내면 됩니다.
                    - 로그인 전에 부르는 API 라 Access Token 이 필요 없습니다.
                    """)
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "로그인 성공"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "idToken 이 비어 있습니다."),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "401",
                description = "Google ID Token 이 유효하지 않거나 만료됐습니다. 로그인을 다시 시도해 주세요. (`AUTH_008`)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "503",
                description = "토큰 저장소(Redis)를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요. (`AUTH_006`)")
    })
    @SecurityRequirements
    @PostMapping("/login/google")
    public ApiResponse<GoogleLoginResponse> loginWithGoogle(
            @Valid @RequestBody GoogleLoginRequest request, HttpServletResponse response) {
        GoogleLoginResult result = googleLoginUseCase.login(new GoogleLoginCommand(request.idToken()));
        refreshTokenCookieManager.write(response, result.refreshToken());
        return ApiResponse.success(GoogleLoginResponse.from(result));
    }

    @Operation(summary = "Access Token 재발급", description = """
                    Access Token 이 만료됐을 때 호출합니다. 요청 본문은 없고, 브라우저가 자동으로 실어 보내는 `refresh_token` 쿠키만으로 동작합니다(fetch 는 credentials 를 include 로).

                    - 호출할 때마다 Refresh Token 도 **새 값으로 교체**됩니다(회전). 예전 토큰은 즉시 무효라 같은 토큰으로 두 번 재발급할 수 없습니다.
                    - 401 이 오면 다시 로그인시켜야 합니다.
                    """)
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "재발급 성공"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "401",
                description = "Refresh Token 이 없거나 만료·이미 사용된 값입니다. 다시 로그인해야 합니다. (`AUTH_002`)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "허용되지 않은 출처에서의 재발급 요청입니다. (`AUTH_007`)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "503",
                description = "토큰 저장소(Redis)를 사용할 수 없습니다. (`AUTH_006`)")
    })
    @SecurityRequirements
    @PostMapping("/refresh")
    public ApiResponse<RotateRefreshTokenResponse> refresh(HttpServletRequest request, HttpServletResponse response) {
        RotateRefreshTokenResult result;
        try {
            result = rotateRefreshTokenUseCase.rotate(new RotateRefreshTokenCommand(
                    refreshTokenCookieManager.find(request).orElse(null)));
        } catch (InvalidRefreshTokenException invalid) {
            // 무효한 토큰을 브라우저가 계속 들고 있으면 새로고침마다 같은 401 이 나고 스스로 회복되지 않는다.
            // 사용자가 쿠키를 직접 지워야만 벗어난다. 여기서 만료시켜 다음 요청부터는 보내지 않게 한다.
            //
            // RefreshSessionUnavailableException(Redis 장애)은 일부러 잡지 않는다. 그때는 토큰이 무효한지
            // 알 수 없는 상태라, 지우면 Redis 가 한 번 깜빡일 때 접속 중인 전원이 로그아웃된다.
            refreshTokenCookieManager.clear(response);
            throw invalid;
        }
        refreshTokenCookieManager.write(response, result.refreshToken());
        return ApiResponse.success(RotateRefreshTokenResponse.from(result));
    }

    @Operation(summary = "로그아웃", description = """
                    현재 세션을 끝냅니다. `refresh_token` 쿠키로 식별한 세션을 서버에서 지우고, 쿠키 자체도 지웁니다.

                    - 쿠키가 없거나 이미 무효한 토큰이어도 실패하지 않습니다. 어차피 도달하려는 상태(로그아웃됨)는 이미 달성된 것이기 때문입니다.
                    - 이 호출 이후에는 이전 Refresh Token 으로 `/refresh` 를 호출해도 새 Access Token 을 받을 수 없습니다.
                    """)
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "로그아웃 완료(항상 성공)")
    })
    @SecurityRequirements
    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        logoutUseCase.logout(
                new LogoutCommand(refreshTokenCookieManager.find(request).orElse(null)));
        refreshTokenCookieManager.clear(response);
        return ApiResponse.success();
    }
}
