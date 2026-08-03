package com.a105.zani.session.presentation.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 강사 제어 요청.
 *
 * <p>동작을 값으로 받는 이유: 강제 퇴장·공유 중지가 나중에 붙더라도 경로가 늘어나지 않는다. 지금 허용하는 값은 {@code MUTE} 하나이고, <b>해제({@code UNMUTE})는 없다</b> —
 * 강사가 남의 마이크를 켤 수 있으면 본인 모르게 소리가 나가기 시작한다(FRD §10.5).
 */
@Schema(description = "강사 제어 요청")
public record ModerationRequest(
        @Schema(
                description = "수행할 제어. 지금은 MUTE 만 지원한다",
                allowableValues = {"MUTE"},
                example = "MUTE",
                requiredMode = Schema.RequiredMode.REQUIRED)
        // enum 으로 받지 않는 이유: 모르는 값이 오면 Jackson 이 역직렬화 단계에서 던지는데, 전역 처리기가
        // 그 예외를 다루지 않아 응답 형태가 다른 오류와 달라진다. 검증으로 받으면 400 이 같은 모양으로 나간다.
        @NotNull @Pattern(regexp = "MUTE", message = "지원하지 않는 제어입니다") String action,

        @Schema(description = "대상 세션 참가자 ID", example = "732194837465", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull Long targetParticipantId) {}
