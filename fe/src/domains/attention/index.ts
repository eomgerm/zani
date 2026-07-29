// attention 도메인의 공개 API. 다른 도메인/app에서는 이 파일을 통해서만 접근한다.
export { useAttentionDetection } from "./presentation/useAttentionDetection";
export type {
  AttentionDetectionState,
  CameraAvailability,
  UseAttentionDetectionOptions,
} from "./presentation/useAttentionDetection";
export { ATTENTION_LABELS } from "./domain/attentionPrediction";
export type {
  AttentionLabel,
  AttentionPrediction,
  AttentionStatus,
} from "./domain/attentionPrediction";
export { CoachingPromptPanel } from "./presentation/CoachingPromptPanel";
export type {
  CoachingPromptOption,
  CoachingPromptPanelProps,
} from "./presentation/CoachingPromptPanel";
export { usePromptTimer } from "./presentation/usePromptTimer";
export {
  POSTURE_GUIDE_COOLDOWN_MS,
  POSTURE_GUIDE_SECONDS,
  usePostureGuidePrompt,
} from "./presentation/usePostureGuidePrompt";
export type {
  PostureGuidePrompt,
  UsePostureGuidePromptOptions,
} from "./presentation/usePostureGuidePrompt";
export {
  CAMERA_GUIDE_OFF_DURATION_MS,
  CAMERA_GUIDE_REMINDER_MS,
  CAMERA_GUIDE_SECONDS,
  useCameraGuidePrompt,
} from "./presentation/useCameraGuidePrompt";
export type {
  CameraGuideAnswer,
  CameraGuideCause,
  CameraGuidePrompt,
  UseCameraGuidePromptOptions,
} from "./presentation/useCameraGuidePrompt";
export type { CameraGuideSuppressionStore } from "./infrastructure/cameraGuideSuppression";
export {
  UNDERSTANDING_CHECK_COOLDOWN_MS,
  UNDERSTANDING_CHECK_SECONDS,
  useUnderstandingCheckPrompt,
} from "./presentation/useUnderstandingCheckPrompt";
export type {
  UnderstandingCheckPrompt,
  UnderstandingCheckResponse,
  UseUnderstandingCheckPromptOptions,
} from "./presentation/useUnderstandingCheckPrompt";
