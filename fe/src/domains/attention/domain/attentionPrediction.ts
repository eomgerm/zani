/**
 * 학습된 참여도 모델의 4-class 라벨. 순서까지 학습 계약(`mediapipe_98_v1`)과 같아야 하며,
 * 이 순서가 곧 모델 출력 logit 의 인덱스다.
 *
 * 라벨 문자열은 학습·export 쪽 계약이므로 도메인 이름과 별개로 그대로 유지한다.
 * 바꾸면 `validateMetadata` 가 모델을 거부한다.
 */
export const ATTENTION_LABELS = [
  "Not-Engaged",
  "Barely-Engaged",
  "Engaged",
  "Highly-Engaged",
] as const;

export type AttentionLabel = (typeof ATTENTION_LABELS)[number];

/**
 * 10초 창 하나에 대한 판정 결과.
 *
 * Worker 경계를 넘어오는 값이라 구조화 복제로 안전한 타입만 담는다. 브라우저 메모리에만
 * 존재하며 서버로 보내지 않는다.
 */
export interface AttentionPrediction {
  readonly label: AttentionLabel;
  /** `ATTENTION_LABELS` 와 같은 순서의 확률 4개. 합은 1이다. */
  readonly probabilities: readonly number[];
}

/**
 * 판정 1건이 나온 10초 창의 메타데이터. 판정 결과를 서버에 보고할 때 함께 실어야 하는 값이다.
 *
 * <p>프레임·랜드마크는 담지 않는다. 창의 "길이"와 "얼마나 잘 보였는지"만 담는다.
 */
export interface AttentionWindow {
  /** 창 길이(초). 판정 단위는 10초다. */
  readonly durationSec: number;
  /** 유효 프레임 비율(0~1). 얼굴을 찾은 프레임 비율이다. */
  readonly signalQuality: number;
}

/**
 * 상위(강의실 화면)에 알리는 판정 상태. 카메라 프레임이나 랜드마크는 절대 올려보내지
 * 않고 이 상태와 `AttentionPrediction` 만 전달한다.
 *
 * - `idle`: 카메라 OFF 등으로 판정을 하지 않는 상태
 * - `preparing`: MediaPipe·ONNX 모델 준비 중
 * - `collecting`: 10초 창을 수집하는 중
 * - `measuring`: 판정이 주기적으로 산출되는 정상 상태
 * - `unmeasurable`: 얼굴 미검출·유효 프레임 부족으로 측정 불가
 * - `permissionDenied`: 카메라 권한 거부
 * - `unavailable`: 모델 로드 실패. 판정만 비활성이고 수업은 계속된다.
 */
export type AttentionStatus =
  | "idle"
  | "preparing"
  | "collecting"
  | "measuring"
  | "unmeasurable"
  | "permissionDenied"
  | "unavailable";
