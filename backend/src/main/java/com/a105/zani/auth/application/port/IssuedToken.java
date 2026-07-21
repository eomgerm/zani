package com.a105.zani.auth.application.port;

import java.time.Instant;

public record IssuedToken(String value, Instant expiresAt) {}
