package com.a105.zani.auth.application.logout;

import org.springframework.stereotype.Service;

import com.a105.zani.auth.application.exception.InvalidRefreshTokenException;
import com.a105.zani.auth.application.port.RefreshSessionPort;
import com.a105.zani.auth.application.port.TokenClaims;
import com.a105.zani.auth.application.port.TokenProvider;
import com.a105.zani.auth.application.port.TokenType;

@Service
public class LogoutService implements LogoutUseCase {

    private final TokenProvider tokenProvider;
    private final RefreshSessionPort refreshSessionPort;

    public LogoutService(TokenProvider tokenProvider, RefreshSessionPort refreshSessionPort) {
        this.tokenProvider = tokenProvider;
        this.refreshSessionPort = refreshSessionPort;
    }

    /** 로그아웃은 항상 성공한다 — refresh 토큰이 없거나 이미 무효해도 클라이언트가 도달하려는 최종 상태(세션 없음)는 이미 달성된 것이므로 예외를 던지지 않는다. */
    @Override
    public void logout(LogoutCommand command) {
        if (command.refreshToken() == null || command.refreshToken().isBlank()) {
            return;
        }

        try {
            TokenClaims claims = tokenProvider.parse(command.refreshToken());
            if (claims.tokenType() == TokenType.REFRESH) {
                refreshSessionPort.revoke(claims.subject(), claims.tokenId());
            }
        } catch (InvalidRefreshTokenException ignored) {
            // 이미 만료·위조된 토큰 — 어차피 무효하므로 무시한다.
        }
    }
}
