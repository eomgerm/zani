package com.a105.zani.auth.application.logout;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.a105.zani.auth.application.exception.InvalidRefreshTokenException;
import com.a105.zani.auth.application.exception.RefreshSessionUnavailableException;
import com.a105.zani.auth.application.port.RefreshSessionPort;
import com.a105.zani.auth.application.port.TokenClaims;
import com.a105.zani.auth.application.port.TokenProvider;
import com.a105.zani.auth.application.port.TokenType;

@Service
public class LogoutService implements LogoutUseCase {

    private static final Logger log = LoggerFactory.getLogger(LogoutService.class);

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
        } catch (RefreshSessionUnavailableException exception) {
            // 세션은 지우지 못했지만 쿠키는 지워지고 세션도 TTL 로 소멸하므로 로그아웃은 성공으로 처리한다.
            log.warn("Redis 장애로 refresh 세션을 무효화하지 못했습니다.", exception);
        }
    }
}
