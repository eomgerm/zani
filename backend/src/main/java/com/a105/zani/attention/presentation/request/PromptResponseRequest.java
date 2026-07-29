package com.a105.zani.attention.presentation.request;

import java.time.Instant;
import java.util.Map;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;

import com.a105.zani.attention.domain.model.PromptAnswer;
import com.a105.zani.attention.domain.model.PromptKind;

/** 학생이 이해 확인 프롬프트에 낸 답. 30초가 지나 자동으로 닫힌 경우에도 {@code NON_RESPONSE}로 보낸다. */
@Schema(description = "이해 확인 프롬프트 응답. 답이 없으면 브라우저가 30초 뒤 NON_RESPONSE로 보낸다.")
public record PromptResponseRequest(
        @Schema(description = """
                                프롬프트 종류. 서버가 기록하는 것은 이해 확인뿐이다.
                                자세 안내와 카메라 안내는 브라우저 안에서 끝나고 서버로 보내지 않는다.""", example = "UNDERSTANDING_CHECK", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull PromptKind kind,

        @Schema(description = """
                                고른 답.
                                OK(이해했어요) / CONFUSED(헷갈려요) / MISSED(놓쳤어요) / NON_RESPONSE(30초 무응답)""", example = "CONFUSED", requiredMode = Schema.RequiredMode.REQUIRED) @NotNull PromptAnswer answer,

        @Schema(
                description = "프롬프트를 화면에 띄운 시각(UTC). 같은 프롬프트인지 알아보는 기준이라 재시도할 때도 같은 값을 보낸다.",
                example = "2026-07-28T09:00:00Z",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull Instant shownAt,

        @Schema(
                description = "학생이 답한 시각(UTC). 무응답이면 패널이 자동으로 닫힌 시각을 보낸다.",
                example = "2026-07-28T09:00:08Z",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull Instant respondedAt,

        @JsonAnySetter @Schema(hidden = true) Map<String, Object> unsupportedFields) {

    /**
     * 계약에 없는 필드가 실려 오지 않았는지.
     *
     * <p>기본 설정에서 Jackson은 모르는 필드를 조용히 버린다. 판정 이벤트와 같은 이유로, 계약 밖의 값이 소리 없이 사라지지 않도록 명시적으로 거절한다.
     */
    @JsonIgnore
    @Schema(hidden = true)
    @AssertTrue(message = "Unsupported fields are not allowed") public boolean isFreeOfUnsupportedFields() {
        return unsupportedFields == null || unsupportedFields.isEmpty();
    }
}
