import type { FrameLandmarkerValues, LandmarkPoint } from "./frameContracts";

/**
 * 프레임 1장에서 49차원 특징을 뽑는 `mediapipe_98_v1` 추출기.
 * `ai/web/engagement-demo/src/features.ts` 에서 이식했다. 값 순서는 학습 계약이므로
 * 모델을 다시 학습하지 않는 한 바꿀 수 없다.
 */

export const SCHEMA_NAME = "mediapipe_98_v1" as const;
export const RAW_FEATURE_COUNT = 49;
export const TOKEN_FEATURE_COUNT = 98;

export const BLENDSHAPE_NAMES = [
  "browDownLeft", "browDownRight", "browInnerUp", "browOuterUpLeft",
  "browOuterUpRight", "cheekPuff", "cheekSquintLeft", "cheekSquintRight",
  "eyeBlinkLeft", "eyeBlinkRight", "eyeLookDownLeft", "eyeLookDownRight",
  "eyeLookInLeft", "eyeLookInRight", "eyeLookOutLeft", "eyeLookOutRight",
  "eyeLookUpLeft", "eyeLookUpRight", "eyeSquintLeft", "eyeSquintRight",
  "eyeWideLeft", "eyeWideRight", "jawOpen", "mouthClose", "mouthFrownLeft",
  "mouthFrownRight", "mouthFunnel", "mouthPucker", "mouthSmileLeft",
  "mouthSmileRight", "mouthStretchLeft", "mouthStretchRight",
  "mouthUpperUpLeft", "mouthUpperUpRight", "noseSneerLeft",
] as const;

const EPSILON = 1e-6;

function pointAt(points: readonly LandmarkPoint[], index: number): LandmarkPoint {
  const point = points[index];
  if (!point) throw new Error(`MediaPipe landmark ${index}가 없습니다.`);
  return point;
}

function irisCenter(points: readonly LandmarkPoint[], start: number): [number, number] {
  let x = 0;
  let y = 0;
  for (let index = start; index < start + 5; index += 1) {
    const point = pointAt(points, index);
    x += point.x;
    y += point.y;
  }
  return [x / 5, y / 5];
}

function axisPosition(
  point: readonly [number, number],
  start: LandmarkPoint,
  end: LandmarkPoint,
): number {
  const axisX = end.x - start.x;
  const axisY = end.y - start.y;
  const denominator = Math.max(axisX * axisX + axisY * axisY, EPSILON);
  return ((point[0] - start.x) * axisX + (point[1] - start.y) * axisY) / denominator;
}

function eyePosition(
  iris: readonly [number, number],
  points: readonly LandmarkPoint[],
  indices: readonly [number, number, number, number],
): [number, number] {
  const [outer, inner, upper, lower] = indices;
  return [
    axisPosition(iris, pointAt(points, outer), pointAt(points, inner)),
    axisPosition(iris, pointAt(points, upper), pointAt(points, lower)),
  ];
}

function matrixValue(matrix: readonly number[], row: number, column: number): number {
  const value = matrix[row * 4 + column];
  if (value === undefined) throw new Error("4x4 얼굴 변환 행렬이 필요합니다.");
  return value;
}

function matrixToEulerXyz(matrix: readonly number[]): [number, number, number] {
  const m00 = matrixValue(matrix, 0, 0);
  const m10 = matrixValue(matrix, 1, 0);
  const m20 = matrixValue(matrix, 2, 0);
  const horizontal = Math.hypot(m00, m10);
  const pitch = Math.atan2(-m20, horizontal);
  if (horizontal < EPSILON) {
    const roll = Math.atan2(-matrixValue(matrix, 1, 2), matrixValue(matrix, 1, 1));
    return [0, pitch, roll];
  }
  const roll = Math.atan2(matrixValue(matrix, 2, 1), matrixValue(matrix, 2, 2));
  const yaw = Math.atan2(m10, m00);
  return [yaw, pitch, roll];
}

export function extractFrameFeatures(input: FrameLandmarkerValues): Float32Array {
  if (input.landmarks.length !== 478 || input.transform.length !== 16) {
    throw new Error("478개 landmark와 4x4 얼굴 변환 행렬이 필요합니다.");
  }
  const right = eyePosition(irisCenter(input.landmarks, 468), input.landmarks, [33, 133, 159, 145]);
  const left = eyePosition(irisCenter(input.landmarks, 473), input.landmarks, [263, 362, 386, 374]);
  const gaze = [
    ...right,
    ...left,
    (right[0] + left[0]) / 2,
    (right[1] + left[1]) / 2,
    right[0] - left[0],
    right[1] - left[1],
  ];
  const [yaw, pitch, roll] = matrixToEulerXyz(input.transform);
  const rightOuter = pointAt(input.landmarks, 33);
  const leftOuter = pointAt(input.landmarks, 263);
  const interocular = Math.max(
    Math.hypot(rightOuter.x - leftOuter.x, rightOuter.y - leftOuter.y),
    EPSILON,
  );
  const nose = pointAt(input.landmarks, 1);
  const head = [yaw, pitch, roll, nose.x, nose.y, 1 / interocular];
  const face = BLENDSHAPE_NAMES.map((name) => input.blendshapes.get(name) ?? 0);
  const result = Float32Array.from([...gaze, ...head, ...face]);
  if (result.length !== RAW_FEATURE_COUNT || result.some((value) => !Number.isFinite(value))) {
    throw new Error("49개의 유효한 MediaPipe 특징이 필요합니다.");
  }
  return result;
}
