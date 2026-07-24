package com.a105.zani.auth.application.googlelogin;

import java.util.UUID;

import org.springframework.stereotype.Service;

import com.a105.zani.auth.application.port.GoogleIdentity;
import com.a105.zani.auth.application.port.GoogleIdentityPort;
import com.a105.zani.auth.application.port.IssuedToken;
import com.a105.zani.auth.application.port.RefreshSession;
import com.a105.zani.auth.application.port.RefreshSessionPort;
import com.a105.zani.auth.application.port.TokenProvider;
import com.a105.zani.member.application.resolve.ResolveGoogleMemberCommand;
import com.a105.zani.member.application.resolve.ResolveGoogleMemberResult;
import com.a105.zani.member.application.resolve.ResolveGoogleMemberUseCase;

@Service
public class GoogleLoginService implements GoogleLoginUseCase {

    private final GoogleIdentityPort googleIdentityPort;
    private final ResolveGoogleMemberUseCase resolveGoogleMemberUseCase;
    private final TokenProvider tokenProvider;
    private final RefreshSessionPort refreshSessionPort;

    public GoogleLoginService(
            GoogleIdentityPort googleIdentityPort,
            ResolveGoogleMemberUseCase resolveGoogleMemberUseCase,
            TokenProvider tokenProvider,
            RefreshSessionPort refreshSessionPort) {
        this.googleIdentityPort = googleIdentityPort;
        this.resolveGoogleMemberUseCase = resolveGoogleMemberUseCase;
        this.tokenProvider = tokenProvider;
        this.refreshSessionPort = refreshSessionPort;
    }

    @Override
    public GoogleLoginResult login(GoogleLoginCommand command) {
        GoogleIdentity identity = googleIdentityPort.verify(command.idToken());

        ResolveGoogleMemberResult resolved = resolveGoogleMemberUseCase.resolve(new ResolveGoogleMemberCommand(
                identity.subject(), identity.email(), identity.name(), identity.pictureUrl()));

        String subject = resolved.memberId().toString();
        String tokenId = UUID.randomUUID().toString();
        IssuedToken accessToken = tokenProvider.issueAccessToken(subject);
        IssuedToken refreshToken = tokenProvider.issueRefreshToken(subject, tokenId);
        refreshSessionPort.create(new RefreshSession(tokenId, subject, refreshToken.expiresAt()));

        return new GoogleLoginResult(
                accessToken,
                refreshToken,
                resolved.email(),
                resolved.displayName(),
                resolved.profileImageUrl(),
                resolved.newMember());
    }
}
