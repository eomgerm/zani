import { FaceLandmarker, FilesetResolver } from "@mediapipe/tasks-vision";

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

/** 프레임의 최소 계약. 이 어댑터는 닫을 수 있다는 것 외에 프레임의 정체를 알 필요가 없다. */
interface ClosableFrame {
  close(): void;
}

/**
 * 49차원 특징 검출 어댑터가 제공하는 모양.
 *
 * <p>계약 타입을 바깥에서 가져오지 않고 여기서 직접 선언한다. 계층 방향이 그렇기 때문이다 — 이 파일은 자기보다 안쪽 계층을 알지 않는다.
 *
 * <p>이 모양이 실제 계약과 맞는지는 둘을 잇는 조립 지점(`useAttentionDetection`)에서 타입으로 확인된다. 어긋나면 그쪽에서 컴파일이 깨지므로, 여기서 계약을 직접 참조하지 않아도 검증은
 * 남는다.
 */
export interface FrameFeatureDetectorAdapter {
  detect(frame: ClosableFrame, timestampMs: number): Float32Array | null;
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

/** MediaPipe 출력과 49차원 특징 추출을 한 어댑터로 감싼다. */
export async function createAttentionFeatureDetector(): Promise<FrameFeatureDetectorAdapter> {
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
