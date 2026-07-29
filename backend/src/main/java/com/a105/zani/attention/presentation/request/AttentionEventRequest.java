package com.a105.zani.attention.presentation.request;

import java.time.Instant;
import java.util.Map;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;

import com.a105.zani.attention.domain.model.DetectorOutcome;

/**
 * 브라우저 검출기가 10초마다 보내는 관측 한 건(확정 문서 §6).
 *
 * <p>보내는 값은 <b>관측</b>이지 학생 상태가 아니다. 집계에 쓰는 학생 상태 6종은 서버가 이 관측과 프롬프트 응답을 합쳐 만든다.
 */
@Schema(description = "검출기 관측 1건. 학생 상태가 아니라 관측 값을 보낸다(상태는 서버가 파생한다).")
public record AttentionEventRequest(
        @Schema(description = """
                                검출기 출력 7종.
                                NOT_ENGAGED / BARELY_ENGAGED / ENGAGED / HIGHLY_ENGAGED — 10초 창을 관측한 4단계
                                UNMEASURABLE — 카메라는 켜져 있으나 얼굴 특징을 못 뽑음
                                CAMERA_OFF — 카메라가 쓸 만한 영상을 못 줌(끔·권한 거부·점유·고장 모두 포함)
                                DETECTOR_UNAVAILABLE — 검출기 자체가 못 돎(MediaPipe·ONNX 로드 실패)""", example = "BARELY_ENGAGED", requiredMode = Schema.RequiredMode.REQUIRED) @NotNull DetectorOutcome outcome,

        @Schema(description = """
                        저참여 여부. 브라우저가 1·2단계 확률의 합이 0.35 이상인지로 판단한 결과다(§3.3).
                        단계값만으로는 되짚을 수 없다 — 가장 높은 값이 3단계여도 아래 두 단계 합이 0.35 면 저참여다.
                        4단계 출력에만 필요하고 나머지 출력에서는 무시된다.""", example = "true") Boolean lowEngagement,

        @Schema(
                description = "10초 창 시작 시각(UTC). CAMERA_OFF·DETECTOR_UNAVAILABLE 은 창 없이 그 자리에서 확정되므로 비운다.",
                example = "2026-07-28T09:00:00Z")
        Instant windowStartedAt,

        @Schema(
                description = "이 값이 정해진 시각(UTC). 창이 있으면 창의 끝이다.",
                example = "2026-07-28T09:00:10Z",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull Instant observedAt,

        @Schema(description = "유효 프레임 비율(0.0~1.0). 창이 없는 출력은 비운다.", example = "0.92")
        @DecimalMin("0.0") @DecimalMax("1.0") Double signalQuality,

        @Schema(
                description = "특징 추출 계약 버전. 모델 학습 계약과 맞지 않으면 판정을 신뢰할 수 없다.",
                example = "mediapipe_98_v1",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(max = 40) String featureSchemaVersion,

        @Schema(
                description = "추론 엔진·모델 버전.",
                example = "engagement-e0g-onnx-1",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(max = 40) String engineVersion,

        @Schema(
                description = "클라이언트가 만든 이벤트 식별자. 재시도 시 같은 값을 보내면 중복 반영되지 않는다.",
                example = "0f9a2c14-6f0e-4f2a-9a1f-6b0f2b7a1c30",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(max = 64) String clientEventId,

        @JsonAnySetter @Schema(hidden = true) Map<String, Object> unsupportedFields) {

    /**
     * 계약에 없는 필드가 실려 오지 않았는지.
     *
     * <p>기본 설정에서 Jackson 은 모르는 필드를 조용히 버린다. 프레임·랜드마크·<b>확률값</b>이 실려 와도 서버가 말없이 200 을 돌려주면 원본 신호를 보내지 않는다는 약속이 깨진 것을 아무도
     * 모른다. 확률은 원본 영상을 보관하지 않아 모델 개선에 쓸 수도 없으므로 받지 않는다.
     */
    @JsonIgnore
    @Schema(hidden = true)
    @AssertTrue(
            message = "Unsupported fields are not allowed (raw frames, landmarks and probabilities must not be sent)")
    public boolean isFreeOfUnsupportedFields() {
        return unsupportedFields == null || unsupportedFields.isEmpty();
    }

    /** 4단계 출력은 저참여 여부가 있어야 한다. 없으면 서버가 연속 카운터를 문서대로 돌릴 수 없다(§3.3·§4.1). */
    @JsonIgnore
    @Schema(hidden = true)
    @AssertTrue(message = "lowEngagement is required for the four engagement levels") public boolean isLowEngagementProvidedForEngagementLevels() {
        return outcome == null || outcome.engagementLevel().isEmpty() || lowEngagement != null;
    }

    /** 10초 창을 봐야 아는 출력은 창 시작 시각이 있어야 한다(§4.2). */
    @JsonIgnore
    @Schema(hidden = true)
    @AssertTrue(message = "windowStartedAt is required for outcomes that need a 10-second observation window") public boolean isWindowConsistentWithOutcome() {
        return outcome == null || !outcome.needsObservationWindow() || windowStartedAt != null;
    }
}
