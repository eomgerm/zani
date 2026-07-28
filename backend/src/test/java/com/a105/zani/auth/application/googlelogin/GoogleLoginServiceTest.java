package com.a105.zani.auth.application.googlelogin;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.a105.zani.auth.application.port.GoogleIdentity;
import com.a105.zani.auth.application.port.GoogleIdentityPort;
import com.a105.zani.auth.application.port.IssuedToken;
import com.a105.zani.auth.application.port.RefreshSession;
import com.a105.zani.auth.application.port.RefreshSessionPort;
import com.a105.zani.auth.application.port.TokenClaims;
import com.a105.zani.auth.application.port.TokenProvider;
import com.a105.zani.member.application.resolve.ResolveGoogleMemberCommand;
import com.a105.zani.member.application.resolve.ResolveGoogleMemberResult;
import com.a105.zani.member.application.resolve.ResolveGoogleMemberUseCase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoogleLoginServiceTest {

    @Test
    void resolvesTheMemberIssuesTokensAndPersistsTheRefreshSession() {
        GoogleIdentity identity = new GoogleIdentity("google-sub-1", "user@example.com", "User", "https://pic");
        StubGoogleIdentityPort identityPort = new StubGoogleIdentityPort(identity);
        RecordingResolveGoogleMemberUseCase resolveUseCase = new RecordingResolveGoogleMemberUseCase(
                new ResolveGoogleMemberResult(7L, "user@example.com", "User", "https://pic", true));
        RecordingTokenProvider tokenProvider = new RecordingTokenProvider();
        RecordingRefreshSessionPort refreshSessionPort = new RecordingRefreshSessionPort();

        GoogleLoginService service =
                new GoogleLoginService(identityPort, resolveUseCase, tokenProvider, refreshSessionPort);

        GoogleLoginResult result = service.login(new GoogleLoginCommand("raw-id-token"));

        assertEquals("raw-id-token", identityPort.verifiedIdToken());
        assertEquals(identity, resolveUseCase.receivedIdentity());
        assertTrue(result.newMember());
        assertEquals("user@example.com", result.email());
        assertEquals("User", result.displayName());
        assertEquals("https://pic", result.profileImageUrl());
        assertEquals("7", tokenProvider.accessTokenSubject());
        assertEquals("7", tokenProvider.refreshTokenSubject());
        assertEquals(tokenProvider.issuedRefreshToken(), result.refreshToken());
        assertEquals(tokenProvider.issuedAccessToken(), result.accessToken());
        assertEquals("7", refreshSessionPort.createdSession().subject());
        assertEquals(
                tokenProvider.issuedRefreshTokenId(),
                refreshSessionPort.createdSession().tokenId());
    }

    private static class StubGoogleIdentityPort implements GoogleIdentityPort {

        private final GoogleIdentity identity;
        private String verifiedIdToken;

        private StubGoogleIdentityPort(GoogleIdentity identity) {
            this.identity = identity;
        }

        @Override
        public GoogleIdentity verify(String idToken) {
            this.verifiedIdToken = idToken;
            return identity;
        }

        String verifiedIdToken() {
            return verifiedIdToken;
        }
    }

    private static class RecordingResolveGoogleMemberUseCase implements ResolveGoogleMemberUseCase {

        private final ResolveGoogleMemberResult result;
        private ResolveGoogleMemberCommand receivedCommand;

        private RecordingResolveGoogleMemberUseCase(ResolveGoogleMemberResult result) {
            this.result = result;
        }

        @Override
        public ResolveGoogleMemberResult resolve(ResolveGoogleMemberCommand command) {
            this.receivedCommand = command;
            return result;
        }

        GoogleIdentity receivedIdentity() {
            return new GoogleIdentity(
                    receivedCommand.googleSubject(),
                    receivedCommand.email(),
                    receivedCommand.displayName(),
                    receivedCommand.profileImageUrl());
        }
    }

    private static class RecordingTokenProvider implements TokenProvider {

        private final IssuedToken accessToken =
                new IssuedToken("access-token", Instant.now().plusSeconds(3600));
        private final IssuedToken refreshToken =
                new IssuedToken("refresh-token", Instant.now().plusSeconds(2_592_000));
        private String accessTokenSubject;
        private String refreshTokenSubject;
        private String refreshTokenId;

        @Override
        public IssuedToken issueAccessToken(String subject) {
            this.accessTokenSubject = subject;
            return accessToken;
        }

        @Override
        public IssuedToken issueRefreshToken(String subject, String tokenId) {
            this.refreshTokenSubject = subject;
            this.refreshTokenId = tokenId;
            return refreshToken;
        }

        @Override
        public TokenClaims parse(String token) {
            throw new UnsupportedOperationException("not needed for this test");
        }

        String accessTokenSubject() {
            return accessTokenSubject;
        }

        String refreshTokenSubject() {
            return refreshTokenSubject;
        }

        String issuedRefreshTokenId() {
            return refreshTokenId;
        }

        IssuedToken issuedAccessToken() {
            return accessToken;
        }

        IssuedToken issuedRefreshToken() {
            return refreshToken;
        }
    }

    private static class RecordingRefreshSessionPort implements RefreshSessionPort {

        private final List<RefreshSession> created = new ArrayList<>();

        @Override
        public void create(RefreshSession session) {
            created.add(session);
        }

        @Override
        public boolean rotate(String currentTokenId, String subject, RefreshSession replacement) {
            throw new UnsupportedOperationException("not needed for this test");
        }

        @Override
        public void revoke(String subject, String tokenId) {
            throw new UnsupportedOperationException("not needed for this test");
        }

        RefreshSession createdSession() {
            return created.get(0);
        }
    }
}
