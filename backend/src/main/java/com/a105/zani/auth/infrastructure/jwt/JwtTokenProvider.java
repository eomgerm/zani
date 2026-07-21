package com.a105.zani.auth.infrastructure.jwt;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.oauth2.jose.jws.JwsAlgorithms;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

import com.a105.zani.auth.application.exception.InvalidRefreshTokenException;
import com.a105.zani.auth.application.exception.TokenProviderException;
import com.a105.zani.auth.application.port.IssuedToken;
import com.a105.zani.auth.application.port.TokenClaims;
import com.a105.zani.auth.application.port.TokenProvider;
import com.a105.zani.auth.application.port.TokenType;

@Component
public class JwtTokenProvider implements TokenProvider {

    private static final String TOKEN_TYPE_CLAIM = "token_type";

    private final JwtProperties properties;
    private final JwtEncoder jwtEncoder;
    private final JwtDecoder jwtDecoder;

    public JwtTokenProvider(
            JwtProperties properties, JwtEncoder jwtEncoder, @Qualifier("jwtDecoder") JwtDecoder jwtDecoder) {
        this.properties = properties;
        this.jwtEncoder = jwtEncoder;
        this.jwtDecoder = jwtDecoder;
    }

    @Override
    public IssuedToken issueAccessToken(String subject) {
        return issue(subject, UUID.randomUUID().toString(), TokenType.ACCESS, properties.accessTokenExpiration());
    }

    @Override
    public IssuedToken issueRefreshToken(String subject, String tokenId) {
        return issue(subject, tokenId, TokenType.REFRESH, properties.refreshTokenExpiration());
    }

    @Override
    public TokenClaims parse(String token) {
        try {
            Jwt jwt = jwtDecoder.decode(token);
            String subject = jwt.getSubject();
            String tokenId = jwt.getId();
            String tokenTypeClaim = jwt.getClaimAsString(TOKEN_TYPE_CLAIM);
            Instant expiresAt = jwt.getExpiresAt();
            if (subject == null
                    || subject.isBlank()
                    || tokenId == null
                    || tokenId.isBlank()
                    || tokenTypeClaim == null
                    || expiresAt == null) {
                throw new InvalidRefreshTokenException();
            }

            return new TokenClaims(subject, tokenId, TokenType.valueOf(tokenTypeClaim), expiresAt);
        } catch (JwtException | IllegalArgumentException exception) {
            throw new InvalidRefreshTokenException(exception);
        }
    }

    private IssuedToken issue(String subject, String tokenId, TokenType tokenType, Duration expiration) {
        try {
            Instant issuedAt = Instant.now();
            Instant expiresAt = issuedAt.plus(expiration);
            JwtClaimsSet claims = JwtClaimsSet.builder()
                    .id(tokenId)
                    .subject(subject)
                    .issuer(properties.issuer())
                    .issuedAt(issuedAt)
                    .expiresAt(expiresAt)
                    .claim(TOKEN_TYPE_CLAIM, tokenType.name())
                    .build();
            JwsHeader header =
                    JwsHeader.with(() -> JwsAlgorithms.HS256).type("JWT").build();
            String token =
                    jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
            return new IssuedToken(token, expiresAt);
        } catch (JwtException | IllegalArgumentException exception) {
            throw new TokenProviderException(exception);
        }
    }
}
