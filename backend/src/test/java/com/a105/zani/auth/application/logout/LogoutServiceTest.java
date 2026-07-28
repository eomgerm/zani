package com.a105.zani.auth.application.logout;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.a105.zani.auth.application.exception.InvalidRefreshTokenException;
import com.a105.zani.auth.application.exception.RefreshSessionUnavailableException;
import com.a105.zani.auth.application.port.IssuedToken;
import com.a105.zani.auth.application.port.RefreshSession;
import com.a105.zani.auth.application.port.RefreshSessionPort;
import com.a105.zani.auth.application.port.TokenClaims;
import com.a105.zani.auth.application.port.TokenProvider;
import com.a105.zani.auth.application.port.TokenType;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LogoutServiceTest {

    @Test
    void doesNothingWhenNoRefreshTokenIsPresent() {
        RecordingRefreshSessionPort refreshSessionPort = new RecordingRefreshSessionPort();
        LogoutService service = new LogoutService(new StubTokenProvider(null), refreshSessionPort);

        assertDoesNotThrow(() -> service.logout(new LogoutCommand(null)));

        assertNull(refreshSessionPort.revokedSubject());
    }

    @Test
    void ignoresAnAlreadyInvalidRefreshToken() {
        RecordingRefreshSessionPort refreshSessionPort = new RecordingRefreshSessionPort();
        LogoutService service = new LogoutService(new StubTokenProvider(null), refreshSessionPort);

        assertDoesNotThrow(() -> service.logout(new LogoutCommand("garbage")));

        assertNull(refreshSessionPort.revokedSubject());
    }

    @Test
    void revokesTheSessionForAValidRefreshToken() {
        TokenClaims claims = new TokenClaims(
                "7", "token-id-1", TokenType.REFRESH, Instant.now().plusSeconds(2_592_000));
        RecordingRefreshSessionPort refreshSessionPort = new RecordingRefreshSessionPort();
        LogoutService service = new LogoutService(new StubTokenProvider(claims), refreshSessionPort);

        service.logout(new LogoutCommand("valid-refresh-token"));

        assertEquals("7", refreshSessionPort.revokedSubject());
        assertEquals("token-id-1", refreshSessionPort.revokedTokenId());
    }

    @Test
    void succeedsEvenWhenTheSessionStoreIsUnavailable() {
        TokenClaims claims = new TokenClaims(
                "7", "token-id-1", TokenType.REFRESH, Instant.now().plusSeconds(2_592_000));
        LogoutService service = new LogoutService(new StubTokenProvider(claims), new UnavailableRefreshSessionPort());

        assertDoesNotThrow(() -> service.logout(new LogoutCommand("valid-refresh-token")));
    }

    @Test
    void doesNotRevokeWhenTheTokenIsNotARefreshToken() {
        TokenClaims claims = new TokenClaims(
                "7", "token-id-1", TokenType.ACCESS, Instant.now().plusSeconds(3600));
        RecordingRefreshSessionPort refreshSessionPort = new RecordingRefreshSessionPort();
        LogoutService service = new LogoutService(new StubTokenProvider(claims), refreshSessionPort);

        service.logout(new LogoutCommand("access-token-used-as-refresh"));

        assertNull(refreshSessionPort.revokedSubject());
    }

    private static class StubTokenProvider implements TokenProvider {

        private final TokenClaims claims;

        private StubTokenProvider(TokenClaims claims) {
            this.claims = claims;
        }

        @Override
        public IssuedToken issueAccessToken(String subject) {
            throw new UnsupportedOperationException("not needed for this test");
        }

        @Override
        public IssuedToken issueRefreshToken(String subject, String tokenId) {
            throw new UnsupportedOperationException("not needed for this test");
        }

        @Override
        public TokenClaims parse(String token) {
            if (claims == null) {
                throw new InvalidRefreshTokenException();
            }
            return claims;
        }
    }

    private static class RecordingRefreshSessionPort implements RefreshSessionPort {

        private String revokedSubject;
        private String revokedTokenId;

        @Override
        public void create(RefreshSession session) {
            throw new UnsupportedOperationException("not needed for this test");
        }

        @Override
        public boolean rotate(String currentTokenId, String subject, RefreshSession replacement) {
            throw new UnsupportedOperationException("not needed for this test");
        }

        @Override
        public void revoke(String subject, String tokenId) {
            this.revokedSubject = subject;
            this.revokedTokenId = tokenId;
        }

        String revokedSubject() {
            return revokedSubject;
        }

        String revokedTokenId() {
            return revokedTokenId;
        }
    }

    private static class UnavailableRefreshSessionPort implements RefreshSessionPort {

        @Override
        public void create(RefreshSession session) {
            throw new UnsupportedOperationException("not needed for this test");
        }

        @Override
        public boolean rotate(String currentTokenId, String subject, RefreshSession replacement) {
            throw new UnsupportedOperationException("not needed for this test");
        }

        @Override
        public void revoke(String subject, String tokenId) {
            throw new RefreshSessionUnavailableException(new RuntimeException("redis down"));
        }
    }
}
