import { describe, expect, it } from "vitest";

import { BLENDSHAPE_NAMES, extractFrameFeatures } from "./features";

function knownLandmarks(): Array<{ x: number; y: number; z: number }> {
  const points = Array.from({ length: 478 }, () => ({ x: 0, y: 0, z: 0 }));
  const set = (index: number, x: number, y: number): void => {
    points[index] = { x, y, z: 0 };
  };
  set(33, 0.1, 0.5); set(133, 0.3, 0.5); set(159, 0.2, 0.4); set(145, 0.2, 0.6);
  set(263, 0.9, 0.5); set(362, 0.7, 0.5); set(386, 0.8, 0.4); set(374, 0.8, 0.6);
  for (let index = 468; index < 473; index += 1) set(index, 0.2, 0.55);
  for (let index = 473; index < 478; index += 1) set(index, 0.8, 0.45);
  set(1, 0.4, 0.6);
  return points;
}

describe("mediapipe_98_v1 frame features", () => {
  it("matches the fixed 49-value feature order", () => {
    const blendshapes = new Map(BLENDSHAPE_NAMES.map((name, index) => [name, index / 100]));
    const values = extractFrameFeatures({
      landmarks: knownLandmarks(),
      transform: [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1],
      blendshapes,
    });

    expect(values).toHaveLength(49);
    [0.5, 0.75, 0.5, 0.25, 0.5, 0.5, 0, 0.5].forEach((expected, index) => {
      expect(values[index]).toBeCloseTo(expected);
    });
    [0, 0, 0, 0.4, 0.6, 1.25].forEach((expected, index) => {
      expect(values[index + 8]).toBeCloseTo(expected);
    });
    values.slice(14).forEach((value, index) => expect(value).toBeCloseTo(index / 100));
  });
});
