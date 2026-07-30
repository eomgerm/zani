package com.a105.zani.session.presentation.response;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

import com.a105.zani.session.application.issuemediatoken.IssueMediaTokenResult;
import com.a105.zani.session.domain.model.SessionStatus;

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

        @Schema(
                description = "LiveKit 토큰 만료 시각(UTC, TTL 10분). 이 값이 지나면 토큰을 다시 발급받아야 한다.",
                example = "2026-07-24T12:30:00Z")
        Instant expiresAt,

        @Schema(
                description = "최대 수업 시간(3시간)에 도달해 수업이 자동 종료될 시각(UTC). 강의실은 이 값으로 종료 10분 전 안내를 띄운다.",
                example = "2026-07-24T15:00:00Z")
        Instant sessionExpiresAt,

        @Schema(description = "강사가 입력한 강의명", example = "React 상태관리 심화")
        String sessionTitle,

        @Schema(description = "세션의 현재 상태. 강사 화면은 PREPARING 을 보면 연결이 끝난 뒤 시작을 호출한다.", example = "LIVE")
        SessionStatus sessionStatus) {

    public static MediaTokenResponse from(IssueMediaTokenResult result) {
        return new MediaTokenResponse(
                result.liveKitUrl(),
                result.accessToken(),
                result.roomName(),
                result.participantIdentity(),
                result.expiresAt(),
                result.sessionExpiresAt(),
                result.sessionTitle(),
                result.sessionStatus());
    }
}
