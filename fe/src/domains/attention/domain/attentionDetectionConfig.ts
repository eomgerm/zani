/** 참여도 검출기의 학습·운영 계약. 임계값은 이 객체에서만 정의한다. */
export const ATTENTION_DETECTION_CONFIG = {
  sampleIntervalMs: 100,
  windowMs: 10_000,
  expectedFrameCount: 100,
  segmentCount: 20,
  minimumValidFrameRatio: 0.7,
  minimumValidFramesPerSegment: 3,
  reportIntervalMs: 10_000,
  lowEngagementProbabilityThreshold: 0.35,
  consecutiveDetectionCount: 3,
} as const;
