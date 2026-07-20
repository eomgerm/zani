package com.a105.zani.auth.application.port;

import java.time.Instant;
import java.util.Objects;

public record RefreshSession(String tokenId, String subject, Instant expiresAt) {

    public RefreshSession {
        if (tokenId == null || tokenId.isBlank()) {
            throw new IllegalArgumentException("tokenId must not be blank");
        }
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("subject must not be blank");
        }
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    }
}
