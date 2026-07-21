package com.a105.zani.auth.application.port;

public interface TokenProvider {

    IssuedToken issueAccessToken(String subject);

    IssuedToken issueRefreshToken(String subject, String tokenId);

    TokenClaims parse(String token);
}
