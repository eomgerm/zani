package com.a105.zani.attention.presentation.response;

import io.swagger.v3.oas.annotations.media.Schema;

import com.a105.zani.attention.application.collect.CollectAttentionEventResult;

@Schema(description = "판정 이벤트 수집 결과")
public record AttentionEventResponse(
        @Schema(description = "이번 요청으로 상태가 반영되었는지", example = "true")
        boolean accepted,

        @Schema(description = "이미 처리한 이벤트라 무시했는지. 재시도는 오류가 아니므로 200으로 응답한다.", example = "false")
        boolean duplicate) {

    public static AttentionEventResponse from(CollectAttentionEventResult result) {
        return new AttentionEventResponse(result.accepted(), result.duplicate());
    }
}
