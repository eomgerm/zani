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
 *
 * <p>§6이 정하는 것은 "검출기 출력 7종을 그대로"뿐이다. 나머지 필드는 전송 자체를 성립시키는 데 필요한 것만 남겼다 — 재시도를 구분하는 {@code clientEventId} 와, 다른 잣대로 잰
 * 판정을 거절하는 {@code featureSchemaVersion} 이다.
 */
@Schema(description = "검출기 관측 1건. 학생 상태가 아니라 관측 값을 보낸다(상태는 서버가 파생한다).")
public record AttentionEventRequest(
        @Schema(description = """
                                검출기 출력 7종.
                                NOT_ENGAGED / BARELY_ENGAGED / ENGAGED / HIGHLY_ENGAGED — 10초 창을 관측한 4단계
                                UNMEASURABLE — 카메라는 켜져 있으나 얼굴 특징을 못 뽑음
                                CAMERA_OFF — 카메라가 쓸 만한 영상을 못 줌(끔·권한 거부·점유·고장 모두 포함)
                                DETECTOR_UNAVAILABLE — 검출기 자체가 못 돎(MediaPipe·ONNX 로드 실패)""", example = "BARELY_ENGAGED", requiredMode = Schema.RequiredMode.REQUIRED) @NotNull DetectorOutcome outcome,

        @Schema(
                description = "이 값이 정해진 시각(UTC). 10초 창이 있으면 창의 끝이다.",
                example = "2026-07-28T09:00:10Z",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull Instant observedAt,

        @Schema(description = "10초 창 시작 시각(UTC). 보내지 않아도 된다 — 수업 후 리포트의 근거로만 쓴다.", example = "2026-07-28T09:00:00Z")
        Instant windowStartedAt,

        @Schema(description = "유효 프레임 비율(0.0~1.0). 보내지 않아도 된다 — 현재 집계에서 읽는 곳이 없고 기록으로만 남는다.", example = "0.92")
        @DecimalMin("0.0") @DecimalMax("1.0") Double signalQuality,

        @Schema(
                description = "특징 추출 계약 버전. 49차원의 구성과 순서까지 학습 계약이라, 어긋난 계약으로 뽑은 판정은 같은 기준으로 읽을 수 없다.",
                example = "mediapipe_98_v1",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(max = 40) String featureSchemaVersion,

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
     *
     * <p>폐기한 {@code lowEngagement}·{@code engineVersion} 도 이 검사에 걸린다. 서버에 소비자가 없어 받지 않기로 했고, 조용히 버리면 클라이언트는 서버가 그 값을 쓰고
     * 있다고 믿는다.
     */
    @JsonIgnore
    @Schema(hidden = true)
    @AssertTrue(
            message = "Unsupported fields are not allowed (raw frames, landmarks and probabilities must not be sent)")
    public boolean isFreeOfUnsupportedFields() {
        return unsupportedFields == null || unsupportedFields.isEmpty();
    }
}
