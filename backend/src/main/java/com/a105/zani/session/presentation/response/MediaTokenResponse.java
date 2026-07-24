package com.a105.zani.session.presentation.response;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

import com.a105.zani.session.application.issuemediatoken.IssueMediaTokenResult;

@Schema(description = "LiveKit 미디어 토큰 발급 응답")
public record MediaTokenResponse(
        @Schema(description = "LiveKit 서버 접속 URL", example = "wss://livekit.example.com")
        String liveKitUrl,

        @Schema(description = "LiveKit 접속용 access token(JWT). 저장·로그 금지, 메모리에만 보관")
        String accessToken,

        @Schema(description = "LiveKit room 이름", example = "zani-local-session-123")
        String roomName,

        @Schema(description = "참가자 identity", example = "p-456")
        String participantIdentity,

        @Schema(description = "토큰 만료 시각(UTC, TTL 10분)", example = "2026-07-24T12:30:00Z")
        Instant expiresAt) {

    public static MediaTokenResponse from(IssueMediaTokenResult result) {
        return new MediaTokenResponse(
                result.liveKitUrl(),
                result.accessToken(),
                result.roomName(),
                result.participantIdentity(),
                result.expiresAt());
    }
}
