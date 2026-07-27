import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
  createAttentionModel,
  softmax,
  validateMetadata,
  type OnnxRuntimeLoader,
} from "./attentionModel";
import { attentionAssetPaths } from "./attentionAssets";

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

interface CreatedSession {
  readonly modelUrl: string;
  readonly executionProviders: readonly string[];
}

describe("createAttentionModel", () => {
  const paths = attentionAssetPaths("/attention");
  let created: CreatedSession[];
  let run: ReturnType<typeof vi.fn>;
  let release: ReturnType<typeof vi.fn>;
  /** WebGPU 세션 생성이 실패하는지. 폴백 경로를 재현할 때 true 로 둔다. */
  let webGpuBroken: boolean;
  let wasmPathsSet: string | null;
  let fetchSpy: ReturnType<typeof vi.spyOn>;

  /** 실제 onnxruntime-web 대신 쓰는 최소 런타임. 우리가 호출하는 표면만 흉내낸다. */
  function fakeRuntime() {
    return {
      env: { wasm: { set wasmPaths(value: string) { wasmPathsSet = value; } } },
      Tensor: class {
        constructor(
          readonly type: string,
          readonly data: Float32Array,
          readonly dims: number[],
        ) {}
      },
      InferenceSession: {
        create: vi.fn(
          async (modelUrl: string, options: { executionProviders: readonly string[] }) => {
            if (webGpuBroken && options.executionProviders.includes("webgpu")) {
              throw new Error("no webgpu");
            }
            created.push({ modelUrl, executionProviders: options.executionProviders });
            return { run, release };
          },
        ),
      },
    };
  }

  const loadRuntime = (() =>
    Promise.resolve(fakeRuntime())) as unknown as OnnxRuntimeLoader;

  /** 메타데이터 응답을 흉내낸다. */
  function metadataResponse(body: unknown, init: { ok?: boolean; json?: boolean } = {}) {
    const { ok = true, json = true } = init;
    return {
      ok,
      headers: { get: () => (json ? "application/json; charset=utf-8" : "text/html") },
      json: async () => body,
    } as unknown as Response;
  }

  beforeEach(() => {
    created = [];
    webGpuBroken = false;
    wasmPathsSet = null;
    release = vi.fn(async () => {});
    // logits 순서상 index 2("Engaged")가 최대값이다.
    run = vi.fn(async () => ({ logits: { data: new Float32Array([0, 1, 5, 2]) } }));
    fetchSpy = vi.spyOn(globalThis, "fetch");
    // WASM 폴백 경로에서 콘솔 잡음을 막는다.
    vi.spyOn(console, "info").mockImplementation(() => {});
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  function build() {
    return createAttentionModel({ paths, loadRuntime });
  }

  it("refuses to start when the exported model is missing", async () => {
    fetchSpy.mockResolvedValue(metadataResponse(null, { ok: false }));

    await expect(build()).rejects.toThrow("학습된 모델이 없습니다");
  });

  it("refuses an HTML error page served in place of the metadata", async () => {
    // 404 를 index.html 로 되돌려주는 서버에서 JSON.parse 실패로 흘러가지 않게 한다.
    fetchSpy.mockResolvedValue(metadataResponse("<!doctype html>", { json: false }));

    await expect(build()).rejects.toThrow("학습된 모델이 없습니다");
  });

  it("refuses metadata whose feature schema does not match the ported pipeline", async () => {
    fetchSpy.mockResolvedValue(
      metadataResponse({ ...validMetadata(), schema: "mediapipe_132_v1" }),
    );

    await expect(build()).rejects.toThrow("지원하지 않는 특징 스키마");
  });

  describe("with a valid model", () => {
    beforeEach(() => {
      fetchSpy.mockResolvedValue(metadataResponse(validMetadata()));
    });

    it("reads the metadata and the model from the configured asset paths", async () => {
      await build();

      expect(fetchSpy).toHaveBeenCalledWith(paths.attentionModelMetadata);
      expect(created[0]?.modelUrl).toBe(paths.attentionModel);
    });

    it("points onnxruntime at the locally served wasm directory", async () => {
      await build();

      expect(wasmPathsSet).toBe(paths.onnxRuntimeWasmBase);
    });

    it("prefers the WebGPU execution provider", async () => {
      await build();

      expect(created).toHaveLength(1);
      expect(created[0]?.executionProviders).toEqual(["webgpu"]);
    });

    it("falls back to the WASM backend where WebGPU is unavailable", async () => {
      webGpuBroken = true;

      const model = await build();

      expect(created).toHaveLength(1);
      expect(created[0]?.executionProviders).toEqual(["wasm"]);
      expect(model.metadata.input_name).toBe("tokens");
    });

    it("turns logits into the winning label and its probabilities", async () => {
      const model = await build();

      const prediction = await model.predict(new Float32Array(20 * 98));

      expect(prediction.label).toBe("Engaged");
      expect(prediction.probabilities).toHaveLength(4);
      expect(prediction.probabilities.reduce((sum, value) => sum + value, 0)).toBeCloseTo(1);
    });

    it("feeds the session a [1, 20, 98] float32 tensor", async () => {
      const model = await build();

      await model.predict(new Float32Array(20 * 98));

      const input = run.mock.calls[0]?.[0]?.tokens;
      expect(input.type).toBe("float32");
      expect(input.dims).toEqual([1, 20, 98]);
    });

    it("rejects a window that is not 20×98 tokens", async () => {
      const model = await build();

      await expect(model.predict(new Float32Array(10))).rejects.toThrow("20×98 토큰");
      expect(run).not.toHaveBeenCalled();
    });

    it("rejects a model that does not return float32 logits", async () => {
      run.mockResolvedValue({ logits: { data: new Int32Array([1, 2, 3, 4]) } });
      const model = await build();

      await expect(model.predict(new Float32Array(20 * 98))).rejects.toThrow("float32 logits");
    });

    it("rejects a model whose output name does not match the metadata", async () => {
      run.mockResolvedValue({ somethingElse: { data: new Float32Array([1, 2, 3, 4]) } });
      const model = await build();

      await expect(model.predict(new Float32Array(20 * 98))).rejects.toThrow("float32 logits");
    });

    it("releases the session on dispose", async () => {
      const model = await build();

      await model.dispose();

      expect(release).toHaveBeenCalledTimes(1);
    });
  });
});
