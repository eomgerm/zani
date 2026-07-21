package com.a105.zani.auth.application.refresh;

import java.util.UUID;

import org.springframework.stereotype.Service;

import com.a105.zani.auth.application.exception.InvalidRefreshTokenException;
import com.a105.zani.auth.application.port.IssuedToken;
import com.a105.zani.auth.application.port.RefreshSession;
import com.a105.zani.auth.application.port.RefreshSessionPort;
import com.a105.zani.auth.application.port.TokenClaims;
import com.a105.zani.auth.application.port.TokenProvider;
import com.a105.zani.auth.application.port.TokenType;

@Service
public class RefreshTokenRotationService implements RotateRefreshTokenUseCase {

    private final TokenProvider tokenProvider;
    private final RefreshSessionPort refreshSessionPort;

    public RefreshTokenRotationService(TokenProvider tokenProvider, RefreshSessionPort refreshSessionPort) {
        this.tokenProvider = tokenProvider;
        this.refreshSessionPort = refreshSessionPort;
    }

    @Override
    public RotateRefreshTokenResult rotate(RotateRefreshTokenCommand command) {
        TokenClaims current = tokenProvider.parse(command.refreshToken());
        if (current.tokenType() != TokenType.REFRESH) {
            throw new InvalidRefreshTokenException();
        }

        String replacementTokenId = UUID.randomUUID().toString();
        IssuedToken accessToken = tokenProvider.issueAccessToken(current.subject());
        IssuedToken refreshToken = tokenProvider.issueRefreshToken(current.subject(), replacementTokenId);
        RefreshSession replacement =
                new RefreshSession(replacementTokenId, current.subject(), refreshToken.expiresAt());

        if (!refreshSessionPort.rotate(current.tokenId(), current.subject(), replacement)) {
            throw new InvalidRefreshTokenException();
        }
        return new RotateRefreshTokenResult(accessToken, refreshToken);
    }
}
