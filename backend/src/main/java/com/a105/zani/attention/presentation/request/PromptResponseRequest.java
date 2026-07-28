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

/** 학생이 프롬프트에 낸 답. 30초가 지나 자동으로 닫힌 경우에도 {@code NO_RESPONSE}로 보낸다. */
@Schema(description = "학생 프롬프트 응답. 답이 없으면 브라우저가 30초 뒤 NO_RESPONSE로 보낸다.")
public record PromptResponseRequest(
        @Schema(description = """
                                프롬프트 종류. UNDERSTANDING_CHECK(이해 확인) / POSTURE_GUIDE(자세 안내) / CAMERA_CHECK(카메라 확인).""", example = "UNDERSTANDING_CHECK", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull PromptKind kind,

        @Schema(description = """
                                고른 답. 종류마다 낼 수 있는 답이 다르다.
                                UNDERSTANDING_CHECK: UNDERSTOOD(이해했어요) / CONFUSED(헷갈려요) / MISSED(놓쳤어요) / NO_RESPONSE
                                POSTURE_GUIDE: ACKNOWLEDGED(확인) / NO_RESPONSE
                                CAMERA_CHECK: CAMERA_UNAVAILABLE(예, 연결이 어렵다) / CAMERA_AVAILABLE(아니오) / NO_RESPONSE""", example = "CONFUSED", requiredMode = Schema.RequiredMode.REQUIRED) @NotNull PromptAnswer answer,

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
