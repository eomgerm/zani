package com.a105.zani.auth.infrastructure.google;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

import com.a105.zani.auth.application.exception.InvalidGoogleIdTokenException;
import com.a105.zani.auth.application.port.GoogleIdentity;
import com.a105.zani.auth.application.port.GoogleIdentityPort;

@Component
public class GoogleIdTokenVerifierAdapter implements GoogleIdentityPort {

    private final JwtDecoder googleIdTokenDecoder;

    public GoogleIdTokenVerifierAdapter(@Qualifier("googleIdTokenDecoder") JwtDecoder googleIdTokenDecoder) {
        this.googleIdTokenDecoder = googleIdTokenDecoder;
    }

    @Override
    public GoogleIdentity verify(String idToken) {
        Jwt jwt;
        try {
            jwt = googleIdTokenDecoder.decode(idToken);
        } catch (JwtException exception) {
            throw new InvalidGoogleIdTokenException(exception);
        }

        String subject = jwt.getSubject();
        String email = jwt.getClaimAsString("email");
        Boolean emailVerified = jwt.getClaimAsBoolean("email_verified");
        if (subject == null
                || subject.isBlank()
                || email == null
                || email.isBlank()
                || !Boolean.TRUE.equals(emailVerified)) {
            throw new InvalidGoogleIdTokenException();
        }

        return new GoogleIdentity(subject, email, jwt.getClaimAsString("name"), jwt.getClaimAsString("picture"));
    }
}
