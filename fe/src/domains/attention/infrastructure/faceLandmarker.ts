import { FaceLandmarker, FilesetResolver } from "@mediapipe/tasks-vision";

import { attentionAssetPaths, type AttentionAssetPaths } from "./attentionAssets";
import type { FrameLandmarkerValues } from "./frameContracts";
import { columnMajorTransformToRowMajor } from "./faceTransform";

/**
 * MediaPipe FaceLandmarker 어댑터. `ai/web/engagement-demo/src/mediapipe.ts` 에서 이식했다.
 *
 * 데모는 wasm 과 모델을 jsDelivr·Google Storage 에서 받았지만, 서비스는 같은 오리진의
 * `public/attention/` 사본을 쓴다. 데모의 디버그 오버레이(`draw`)는 서비스에서 쓰는 곳이
 * 없어 옮기지 않았다.
 */

export interface BrowserFaceLandmarker {
  /** 프레임 1장에서 랜드마크·변환행렬·blendshape 를 뽑는다. 얼굴이 없으면 null. */
  detect(frame: TexImageSource, timestampMs: number): FrameLandmarkerValues | null;
  close(): void;
}

export async function createBrowserFaceLandmarker(
  paths: AttentionAssetPaths = attentionAssetPaths(),
): Promise<BrowserFaceLandmarker> {
  const fileset = await FilesetResolver.forVisionTasks(paths.visionWasmBase);
  const commonOptions = {
    runningMode: "VIDEO" as const,
    numFaces: 1,
    minFaceDetectionConfidence: 0.5,
    minFacePresenceConfidence: 0.5,
    minTrackingConfidence: 0.5,
    outputFaceBlendshapes: true,
    outputFacialTransformationMatrixes: true,
  };
  let landmarker: FaceLandmarker;
  try {
    landmarker = await FaceLandmarker.createFromOptions(fileset, {
      ...commonOptions,
      baseOptions: { modelAssetPath: paths.faceLandmarkerModel, delegate: "GPU" },
    });
  } catch (gpuError) {
    console.info("MediaPipe GPU unavailable; falling back to CPU.", gpuError);
    landmarker = await FaceLandmarker.createFromOptions(fileset, {
      ...commonOptions,
      baseOptions: { modelAssetPath: paths.faceLandmarkerModel, delegate: "CPU" },
    });
  }
  return {
    detect(frame: TexImageSource, timestampMs: number): FrameLandmarkerValues | null {
      const result = landmarker.detectForVideo(frame, timestampMs);
      const landmarks = result.faceLandmarks[0];
      const matrix = result.facialTransformationMatrixes[0];
      if (!landmarks || !matrix || matrix.rows !== 4 || matrix.columns !== 4) return null;
      const categories = result.faceBlendshapes[0]?.categories ?? [];
      return {
        landmarks,
        transform: columnMajorTransformToRowMajor(matrix.data),
        blendshapes: new Map(categories.map((category) => [category.categoryName, category.score])),
      };
    },
    close(): void {
      landmarker.close();
    },
  };
}
