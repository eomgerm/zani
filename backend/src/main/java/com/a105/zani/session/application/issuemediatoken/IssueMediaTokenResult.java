package com.a105.zani.session.application.issuemediatoken;

import java.time.Instant;

public record IssueMediaTokenResult(
        String liveKitUrl, String accessToken, String roomName, String participantIdentity, Instant expiresAt) {}
