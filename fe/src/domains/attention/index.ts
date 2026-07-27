// attention 도메인의 공개 API. 다른 도메인/app에서는 이 파일을 통해서만 접근한다.
export { useEngagementDetection } from "./presentation/useEngagementDetection";
export type {
  CameraAvailability,
  EngagementDetectionState,
  UseEngagementDetectionOptions,
} from "./presentation/useEngagementDetection";
export { ENGAGEMENT_LABELS } from "./domain/engagementPrediction";
export type {
  AttentionStatus,
  EngagementLabel,
  EngagementPrediction,
} from "./domain/engagementPrediction";
