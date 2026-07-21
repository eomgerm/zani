package com.a105.zani.auth.application.port;

import java.time.Instant;

public record TokenClaims(String subject, String tokenId, TokenType tokenType, Instant expiresAt) {}
