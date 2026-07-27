import { describe, expect, it } from "vitest";

import { ATTENTION_ASSET_BASE, attentionAssetPaths } from "./attentionAssets";

describe("attentionAssetPaths", () => {
  it("serves every asset from the same origin so nothing is fetched from a CDN", () => {
    const paths = attentionAssetPaths();

    Object.values(paths).forEach((path) => {
      expect(path.startsWith("/")).toBe(true);
      expect(path).not.toMatch(/^https?:/);
    });
  });

  it("resolves each asset under the configured base directory", () => {
    const paths = attentionAssetPaths("/assets/attention");

    expect(paths.visionWasmBase).toBe("/assets/attention/mediapipe/wasm");
    expect(paths.faceLandmarkerModel).toBe("/assets/attention/mediapipe/face_landmarker.task");
    expect(paths.engagementModel).toBe("/assets/attention/models/engagement.onnx");
    expect(paths.engagementMetadata).toBe("/assets/attention/models/engagement.metadata.json");
  });

  it("keeps a trailing slash on the onnxruntime base that ort.env.wasm.wasmPaths requires", () => {
    expect(attentionAssetPaths("/attention").onnxRuntimeWasmBase).toBe("/attention/onnxruntime/");
  });

  it("tolerates a base written with a trailing slash", () => {
    expect(attentionAssetPaths("/attention/")).toEqual(attentionAssetPaths("/attention"));
  });

  it("defaults to the directory the asset sync script writes into", () => {
    expect(ATTENTION_ASSET_BASE).toBe("/attention");
  });
});
