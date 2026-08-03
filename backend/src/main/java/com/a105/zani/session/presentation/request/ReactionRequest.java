package com.a105.zani.session.presentation.request;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 반응 전송 프레임.
 *
 * <p>이모지 문자가 아니라 종류 이름을 보낸다 — 서버가 받은 문자열을 모든 참가자 화면에 그대로 띄우게 되므로 임의 문자열을 허용할 수 없다. 어떤 그림으로 보일지는 클라이언트가 정한다.
 */
@Schema(description = "반응 전송 프레임(STOMP)")
public record ReactionRequest(
        @Schema(description = "클라이언트가 만든 전송 식별자", example = "r-8f3a1e", requiredMode = Schema.RequiredMode.REQUIRED)
        String clientEventId,

        @Schema(
                description = "반응 종류",
                allowableValues = {"LIKE", "HEART", "CLAP", "CELEBRATE", "WOW", "CHEER"},
                example = "CLAP",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String reaction) {}
