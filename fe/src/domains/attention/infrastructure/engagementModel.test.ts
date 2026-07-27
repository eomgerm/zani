import { describe, expect, it } from "vitest";

import { softmax, validateMetadata } from "./engagementModel";

function validMetadata(): Record<string, unknown> {
  return {
    schema: "mediapipe_98_v1",
    input_name: "tokens",
    input_shape: ["batch", 20, 98],
    output_name: "logits",
    labels: ["Not-Engaged", "Barely-Engaged", "Engaged", "Highly-Engaged"],
    window_seconds: 10,
    segment_count: 20,
    sample_fps: 10,
  };
}

describe("deployment metadata", () => {
  it("accepts the Python exporter contract", () => {
    const metadata = validateMetadata(validMetadata());

    expect(metadata.input_name).toBe("tokens");
    expect(metadata.labels).toHaveLength(4);
  });

  it("rejects unsupported feature schemas", () => {
    expect(() => validateMetadata({ ...validMetadata(), schema: "mediapipe_132_v1" })).toThrow(
      "지원하지 않는 특징 스키마",
    );
  });

  it("rejects a changed class order", () => {
    expect(() => validateMetadata({ ...validMetadata(), labels: ["Engaged"] })).toThrow(
      "클래스 순서",
    );
  });
});

it("converts four logits into probabilities summing to one", () => {
  const probabilities = softmax(new Float32Array([1, 2, 3, 4]));

  expect(probabilities).toHaveLength(4);
  expect(probabilities.reduce((sum, value) => sum + value, 0)).toBeCloseTo(1);
  expect(probabilities[3]).toBeGreaterThan(probabilities[0] ?? 0);
});
