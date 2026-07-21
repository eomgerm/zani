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
  segmentCount: number;
  minimumValidFrames: number;
}
