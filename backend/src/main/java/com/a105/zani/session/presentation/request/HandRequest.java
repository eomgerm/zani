package com.a105.zani.session.presentation.request;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 손들기 상태 변경 프레임.
 *
 * <p>"뒤집어라"가 아니라 원하는 상태를 받는다. 뒤집기로 두면 프레임이 한 번 더 도착했을 때 의도와 반대 상태로 끝난다.
 */
@Schema(description = "손들기 상태 변경 프레임(STOMP)")
public record HandRequest(
        @Schema(
                description = "클라이언트가 만든 전송 식별자. 재시도 시 같은 값을 보낸다.",
                example = "h-8f3a1e",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String clientEventId,

        @Schema(description = "손을 든 상태로 만들려면 true, 내리려면 false", example = "true")
        boolean raised) {}
