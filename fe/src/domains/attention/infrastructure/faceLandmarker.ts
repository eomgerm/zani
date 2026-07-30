import { FaceLandmarker, FilesetResolver } from "@mediapipe/tasks-vision";

import type { AttentionFeatureDetector } from "../application/attentionDetectionPorts";
import { attentionAssetPaths, type AttentionAssetPaths } from "./attentionAssets";
import type { FrameLandmarkerValues } from "./frameContracts";
import { extractFrameFeatures } from "./frameFeatures";
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
  // 정리는 한 번만 한다. 이미 해제된 WASM 인스턴스에 close 를 다시 부르면 예외가 난다.
  let closed = false;
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
    /**
     * MediaPipe 인스턴스를 해제한다. 실패해도 삼킨다.
     *
     * <p>이 정리는 강의실을 떠날 때 일어난다 — 학생이 쫓겨나거나 수업이 끝나 화면을 벗어나는 순간이다. 그때 WASM 힙이나 GPU 컨텍스트가 먼저 정리돼 있으면 close 가 던지는데,
     * 떠나는 중이라 복구할 것도 없고 사용자가 할 수 있는 일도 없다. 그대로 올려보내면 개발 오버레이가 뜨고 퇴장이 실패처럼 보인다.
     */
    close(): void {
      if (closed) return;
      closed = true;
      try {
        landmarker.close();
      } catch (error) {
        console.debug("[attention] MediaPipe 정리 중 예외(무시)", error);
      }
    },
  };
}

/** application 포트에 맞춰 MediaPipe 출력과 49차원 특징 추출을 한 어댑터로 감싼다. */
export async function createAttentionFeatureDetector(): Promise<AttentionFeatureDetector> {
  const landmarker = await createBrowserFaceLandmarker();
  return {
    detect(frame, timestampMs): Float32Array | null {
      const detected = landmarker.detect(frame as TexImageSource, timestampMs);
      return detected === null ? null : extractFrameFeatures(detected);
    },
    close(): void {
      landmarker.close();
    },
  };
}
