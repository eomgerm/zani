package com.a105.zani.attention.presentation.request;

import java.time.Instant;
import java.util.Map;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;

import com.a105.zani.attention.domain.model.AttentionState;

/** 브라우저가 만든 10초 판정 1건. 판정 결과만 담고 원본 신호(프레임·랜드마크·blendshape)는 담지 않는다. */
@Schema(description = "학생 참여도 판정 이벤트. 판정은 브라우저에서 끝나고 결과만 보낸다(원본 영상·랜드마크 전송 금지).")
public record AttentionEventRequest(
        @Schema(description = "판정된 참여 상태", example = "CONFUSED", requiredMode = Schema.RequiredMode.REQUIRED) @NotNull AttentionState type,

        @Schema(
                description = "판정 창 시작 시각(UTC)",
                example = "2026-07-28T09:00:00Z",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull Instant startedAt,

        @Schema(
                description = "판정 창 종료 시각(UTC). 상태 갱신 시각은 서버가 수신 시점으로 따로 기록한다.",
                example = "2026-07-28T09:00:10Z",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull Instant endedAt,

        @Schema(
                description = "판정 창 길이(초). 기본 판정 단위는 10초다.",
                example = "10",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @Min(1) int durationSec,

        @Schema(
                description = "유효 프레임 비율(0.0~1.0). 측정 가능 학생 비율 계산에 쓴다.",
                example = "0.92",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @DecimalMin("0.0") @DecimalMax("1.0") double signalQuality,

        @Schema(
                description = "클라이언트가 만든 이벤트 식별자. 재시도 시 같은 값을 보내면 중복 반영되지 않는다.",
                example = "0f9a2c14-6f0e-4f2a-9a1f-6b0f2b7a1c30",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank String clientEventId,

        @JsonAnySetter @Schema(hidden = true) Map<String, Object> unsupportedFields) {

    /**
     * 계약에 없는 필드가 실려 오지 않았는지.
     *
     * <p>기본 설정에서 Jackson은 모르는 필드를 조용히 버린다. 그러면 프레임·랜드마크가 실수로 실려 와도 서버가 말없이 200을 돌려주고, 원본 신호를 보내지 않는다는 약속이 깨진 것을 아무도
     * 모른다. 그래서 명시적으로 거절한다.
     */
    @JsonIgnore
    @Schema(hidden = true)
    @AssertTrue(message = "Unsupported fields are not allowed (raw frames and landmarks must not be sent)")
    public boolean isFreeOfUnsupportedFields() {
        return unsupportedFields == null || unsupportedFields.isEmpty();
    }
}
