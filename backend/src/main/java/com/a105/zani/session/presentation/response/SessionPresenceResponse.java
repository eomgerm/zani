package com.a105.zani.session.presentation.response;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

import com.a105.zani.session.application.presence.PresenceResult;
import com.a105.zani.session.application.presence.ReconnectStatus;
import com.a105.zani.session.domain.model.ConnectionState;
import com.a105.zani.session.domain.model.SessionParticipantRole;

@Schema(description = "presence heartbeat 응답")
public record SessionPresenceResponse(
        @Schema(description = "참가자 presence 상태") ParticipantState participant,

        @Schema(description = "재연결·세션 상태 신호", example = "CONNECTED")
        ReconnectStatus reconnectStatus,

        @Schema(description = "강사 미복귀로 세션이 종료되었는지", example = "false")
        boolean sessionEnded) {

    @Schema(description = "참가자 presence 상태")
    public record ParticipantState(
            @Schema(description = "세션 참가자 ID", example = "456")
            String participantId,

            @Schema(description = "역할", example = "STUDENT") SessionParticipantRole role,

            @Schema(description = "보고된 연결 상태", example = "CONNECTED")
            ConnectionState connectionState,

            @Schema(description = "heartbeat 시각(UTC)", example = "2026-07-24T12:30:00Z")
            Instant heartbeatAt) {}

    public static SessionPresenceResponse from(PresenceResult result) {
        return new SessionPresenceResponse(
                new ParticipantState(
                        String.valueOf(result.participantId()),
                        result.role(),
                        result.connectionState(),
                        result.heartbeatAt()),
                result.reconnectStatus(),
                result.sessionEnded());
    }
}
