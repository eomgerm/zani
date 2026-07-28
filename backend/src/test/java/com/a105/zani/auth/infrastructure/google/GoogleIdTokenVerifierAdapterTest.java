package com.a105.zani.auth.infrastructure.google;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import com.a105.zani.auth.application.exception.InvalidGoogleIdTokenException;
import com.a105.zani.auth.application.port.GoogleIdentity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GoogleIdTokenVerifierAdapterTest {

    @Test
    void returnsTheIdentityForAValidToken() {
        GoogleIdTokenVerifierAdapter adapter = adapterReturning(jwtWithClaims(Map.of(
                "sub", "google-sub-1",
                "email", "user@example.com",
                "email_verified", true,
                "name", "User",
                "picture", "https://pic")));

        GoogleIdentity identity = adapter.verify("id-token");

        assertEquals("google-sub-1", identity.subject());
        assertEquals("user@example.com", identity.email());
        assertEquals("User", identity.name());
        assertEquals("https://pic", identity.pictureUrl());
    }

    @Test
    void rejectsATokenWhoseEmailIsNotVerified() {
        GoogleIdTokenVerifierAdapter adapter = adapterReturning(jwtWithClaims(Map.of(
                "sub", "google-sub-1",
                "email", "user@example.com",
                "email_verified", false)));

        assertThrows(InvalidGoogleIdTokenException.class, () -> adapter.verify("id-token"));
    }

    @Test
    void rejectsATokenWithoutAnEmailVerifiedClaim() {
        GoogleIdTokenVerifierAdapter adapter = adapterReturning(jwtWithClaims(Map.of(
                "sub", "google-sub-1",
                "email", "user@example.com")));

        assertThrows(InvalidGoogleIdTokenException.class, () -> adapter.verify("id-token"));
    }

    @Test
    void rejectsATokenWithoutASubject() {
        GoogleIdTokenVerifierAdapter adapter =
                adapterReturning(jwtWithClaims(Map.of("email", "user@example.com", "email_verified", true)));

        assertThrows(InvalidGoogleIdTokenException.class, () -> adapter.verify("id-token"));
    }

    @Test
    void rejectsATokenWithoutAnEmail() {
        GoogleIdTokenVerifierAdapter adapter =
                adapterReturning(jwtWithClaims(Map.of("sub", "google-sub-1", "email_verified", true)));

        assertThrows(InvalidGoogleIdTokenException.class, () -> adapter.verify("id-token"));
    }

    @Test
    void wrapsADecodingFailureInAnInvalidGoogleIdTokenException() {
        GoogleIdTokenVerifierAdapter adapter = new GoogleIdTokenVerifierAdapter(token -> {
            throw new JwtException("signature verification failed");
        });

        assertThrows(InvalidGoogleIdTokenException.class, () -> adapter.verify("forged-token"));
    }

    private static GoogleIdTokenVerifierAdapter adapterReturning(Jwt jwt) {
        JwtDecoder decoder = token -> jwt;
        return new GoogleIdTokenVerifierAdapter(decoder);
    }

    private static Jwt jwtWithClaims(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("id-token").header("alg", "RS256");
        claims.forEach(builder::claim);
        return builder.build();
    }
}
