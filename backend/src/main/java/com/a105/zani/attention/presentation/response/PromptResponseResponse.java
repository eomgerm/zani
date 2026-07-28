package com.a105.zani.attention.presentation.response;

import io.swagger.v3.oas.annotations.media.Schema;

import com.a105.zani.attention.application.recordpromptresponse.RecordPromptResponseResult;

@Schema(description = "프롬프트 응답 수집 결과")
public record PromptResponseResponse(
        @Schema(description = "이번 요청으로 답이 기록되었는지", example = "true")
        boolean accepted,

        @Schema(description = "같은 프롬프트에 이미 답이 있어 무시했는지. 재시도는 오류가 아니므로 200으로 응답한다.", example = "false")
        boolean duplicate) {

    public static PromptResponseResponse from(RecordPromptResponseResult result) {
        return new PromptResponseResponse(result.accepted(), result.duplicate());
    }
}
