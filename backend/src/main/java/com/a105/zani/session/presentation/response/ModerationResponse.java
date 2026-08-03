package com.a105.zani.session.presentation.response;

import io.swagger.v3.oas.annotations.media.Schema;

import com.a105.zani.session.application.moderation.MuteParticipantResult;

/** @param alreadyMuted 이미 음소거 상태였는지. 같은 요청의 재시도이며 <b>성공으로 다룬다</b> — 결과가 같으므로 화면이 다르게 처리할 이유가 없다 */
@Schema(description = "강사 제어 결과")
public record ModerationResponse(
        @Schema(description = "이 호출 뒤 대상이 음소거 상태인지", example = "true")
        boolean muted,

        @Schema(description = "이미 음소거였는지(재시도)", example = "false")
        boolean alreadyMuted) {

    public static ModerationResponse from(MuteParticipantResult result) {
        return new ModerationResponse(result.muted(), result.alreadyMuted());
    }
}
