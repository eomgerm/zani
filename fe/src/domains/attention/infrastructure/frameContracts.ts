/**
 * MediaPipe FaceLandmarker 출력과 특징 추출 사이의 어댑터 계약.
 * `ai/web/engagement-demo/src/contracts.ts` 에서 이식했다.
 */

export interface LandmarkPoint {
  x: number;
  y: number;
  z: number;
}

export interface FrameLandmarkerValues {
  landmarks: readonly LandmarkPoint[];
  transform: readonly number[];
  blendshapes: ReadonlyMap<string, number>;
}

export interface TimedFrameFeatures {
  timestampMs: number;
  values: Float32Array | null;
}

export interface WindowOptions {
  windowMs: number;
  expectedFrameCount: number;
  segmentCount: number;
  minimumValidFrameRatio: number;
  minimumValidFrames: number;
}
