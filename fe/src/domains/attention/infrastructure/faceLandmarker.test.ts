import { beforeEach, describe, expect, it, vi } from "vitest";

import { attentionAssetPaths } from "./attentionAssets";

/**
 * MediaPipe 는 wasm 을 실제로 로드하므로 jsdom 에서 돌 수 없다. 우리가 호출하는 표면만
 * 가짜로 두고, 자산 경로 전달·GPU→CPU 폴백·프레임 변환 규칙을 검증한다.
 */
const mediapipe = vi.hoisted(() => {
  interface CreateCall {
    readonly fileset: unknown;
    readonly modelAssetPath: string;
    readonly delegate: string;
  }
  const state = {
    visionTasksArg: null as string | null,
    createCalls: [] as CreateCall[],
    gpuBroken: false,
    detectResult: {} as Record<string, unknown>,
    close: vi.fn(),
    detectForVideo: vi.fn(),
  };
  return { state };
});

vi.mock("@mediapipe/tasks-vision", () => ({
  FilesetResolver: {
    forVisionTasks: vi.fn(async (base: string) => {
      mediapipe.state.visionTasksArg = base;
      return { fileset: base };
    }),
  },
  FaceLandmarker: {
    createFromOptions: vi.fn(
      async (
        fileset: unknown,
        options: { baseOptions: { modelAssetPath: string; delegate: string } },
      ) => {
        if (mediapipe.state.gpuBroken && options.baseOptions.delegate === "GPU") {
          throw new Error("no gpu delegate");
        }
        mediapipe.state.createCalls.push({
          fileset,
          modelAssetPath: options.baseOptions.modelAssetPath,
          delegate: options.baseOptions.delegate,
        });
        return {
          detectForVideo: mediapipe.state.detectForVideo,
          close: mediapipe.state.close,
        };
      },
    ),
  },
}));

const { createBrowserFaceLandmarker } = await import("./faceLandmarker");

/** MediaPipe 가 실제로 내보내는 모양의 결과. transform 은 column-major 다. */
function landmarkerResult(
  overrides: Partial<{
    faceLandmarks: unknown[];
    facialTransformationMatrixes: unknown[];
    faceBlendshapes: unknown[];
  }> = {},
) {
  return {
    faceLandmarks: [[{ x: 0.1, y: 0.2, z: 0.3 }]],
    facialTransformationMatrixes: [
      {
        rows: 4,
        columns: 4,
        data: [0, 1, 0, 0, -1, 0, 0, 0, 0, 0, 1, 0, 10, 20, 30, 1],
      },
    ],
    faceBlendshapes: [{ categories: [{ categoryName: "jawOpen", score: 0.42 }] }],
    ...overrides,
  };
}

describe("createBrowserFaceLandmarker", () => {
  const paths = attentionAssetPaths("/attention");
  const video = {} as HTMLVideoElement;

  beforeEach(() => {
    mediapipe.state.visionTasksArg = null;
    mediapipe.state.createCalls = [];
    mediapipe.state.gpuBroken = false;
    mediapipe.state.close = vi.fn();
    mediapipe.state.detectForVideo = vi.fn(() => landmarkerResult());
    vi.spyOn(console, "info").mockImplementation(() => {});
  });

  it("loads the wasm directory and the model from the configured asset paths", async () => {
    await createBrowserFaceLandmarker(paths);

    expect(mediapipe.state.visionTasksArg).toBe(paths.visionWasmBase);
    expect(mediapipe.state.createCalls[0]?.modelAssetPath).toBe(paths.faceLandmarkerModel);
  });

  it("prefers the GPU delegate", async () => {
    await createBrowserFaceLandmarker(paths);

    expect(mediapipe.state.createCalls).toHaveLength(1);
    expect(mediapipe.state.createCalls[0]?.delegate).toBe("GPU");
  });

  it("falls back to the CPU delegate where the GPU delegate is unavailable", async () => {
    mediapipe.state.gpuBroken = true;

    const landmarker = await createBrowserFaceLandmarker(paths);

    expect(mediapipe.state.createCalls).toHaveLength(1);
    expect(mediapipe.state.createCalls[0]?.delegate).toBe("CPU");
    expect(landmarker.detect(video, 0)).not.toBeNull();
  });

  it("converts the column-major transform MediaPipe emits into row-major order", async () => {
    const landmarker = await createBrowserFaceLandmarker(paths);

    expect(landmarker.detect(video, 1_000)?.transform).toEqual([
      0, -1, 0, 10,
      1, 0, 0, 20,
      0, 0, 1, 30,
      0, 0, 0, 1,
    ]);
  });

  it("exposes blendshapes keyed by category name", async () => {
    const landmarker = await createBrowserFaceLandmarker(paths);

    expect(landmarker.detect(video, 0)?.blendshapes.get("jawOpen")).toBe(0.42);
  });

  it("passes the frame timestamp through to MediaPipe's video mode", async () => {
    const landmarker = await createBrowserFaceLandmarker(paths);

    landmarker.detect(video, 1_234);

    expect(mediapipe.state.detectForVideo).toHaveBeenCalledWith(video, 1_234);
  });

  it("reports no face when MediaPipe finds none", async () => {
    mediapipe.state.detectForVideo = vi.fn(() =>
      landmarkerResult({ faceLandmarks: [], facialTransformationMatrixes: [] }),
    );
    const landmarker = await createBrowserFaceLandmarker(paths);

    expect(landmarker.detect(video, 0)).toBeNull();
  });

  it("reports no face when the transform is not a 4x4 matrix", async () => {
    mediapipe.state.detectForVideo = vi.fn(() =>
      landmarkerResult({
        facialTransformationMatrixes: [{ rows: 3, columns: 3, data: [1, 0, 0, 0, 1, 0, 0, 0, 1] }],
      }),
    );
    const landmarker = await createBrowserFaceLandmarker(paths);

    expect(landmarker.detect(video, 0)).toBeNull();
  });

  it("treats a frame without blendshapes as all-zero rather than failing", async () => {
    mediapipe.state.detectForVideo = vi.fn(() => landmarkerResult({ faceBlendshapes: [] }));
    const landmarker = await createBrowserFaceLandmarker(paths);

    expect(landmarker.detect(video, 0)?.blendshapes.size).toBe(0);
  });

  it("closes the underlying landmarker so the wasm instance is released", async () => {
    const landmarker = await createBrowserFaceLandmarker(paths);

    landmarker.close();

    expect(mediapipe.state.close).toHaveBeenCalledTimes(1);
  });
});
