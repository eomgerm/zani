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
  UNDERSTANDING_CHECK_COOLDOWN_MS,
  UNDERSTANDING_CHECK_SECONDS,
  useUnderstandingCheckPrompt,
} from "./presentation/useUnderstandingCheckPrompt";
export type {
  UnderstandingCheckPrompt,
  UnderstandingCheckResponse,
  UseUnderstandingCheckPromptOptions,
} from "./presentation/useUnderstandingCheckPrompt";
