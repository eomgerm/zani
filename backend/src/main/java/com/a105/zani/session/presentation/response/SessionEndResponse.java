package com.a105.zani.session.presentation.response;

import io.swagger.v3.oas.annotations.media.Schema;

import com.a105.zani.session.application.end.EndSessionResult;
import com.a105.zani.session.domain.model.SessionStatus;

@Schema(description = "수업 종료 응답")
public record SessionEndResponse(
        @Schema(description = "세션 ID", example = "123") Long sessionId,

        @Schema(description = "종료 후 세션 상태", example = "ENDED")
        SessionStatus status,

        @Schema(description = "이번 요청으로 종료되었는지(이미 종료된 세션이면 false)", example = "true")
        boolean ended) {

    public static SessionEndResponse from(EndSessionResult result) {
        return new SessionEndResponse(result.sessionId(), result.status(), result.ended());
    }
}
