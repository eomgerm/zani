/**
 * 판정 파이프라인이 쓰는 정적 자산 경로.
 *
 * Vite 데모는 MediaPipe wasm 과 face landmarker 모델을 CDN 에서 받았지만, 서비스에서는
 * `fe/scripts/sync-attention-assets.mjs` 가 `public/attention/` 아래로 복사한 사본을
 * Next.js 가 직접 서빙한다. 그래서 모든 경로는 같은 오리진의 절대 경로이며 런타임에
 * 외부 호스트로 나가는 요청이 없다.
 */

/** 자산 루트. 배포 경로가 다르면 `NEXT_PUBLIC_ATTENTION_ASSET_BASE` 로 덮어쓴다. */
export const ATTENTION_ASSET_BASE = process.env.NEXT_PUBLIC_ATTENTION_ASSET_BASE ?? "/attention";

export interface AttentionAssetPaths {
  /** MediaPipe `FilesetResolver.forVisionTasks` 에 넘기는 wasm 디렉터리. */
  readonly visionWasmBase: string;
  /** FaceLandmarker `modelAssetPath`. */
  readonly faceLandmarkerModel: string;
  /** `ort.env.wasm.wasmPaths`. onnxruntime-web 이 접두사로 이어 붙이므로 슬래시로 끝난다. */
  readonly onnxRuntimeWasmBase: string;
  /**
   * 학습된 참여도 모델. 파일명 `engagement.onnx` 는 ai/ 의 export 명령이 정하는 이름이라
   * attention 으로 바꾸지 않는다(바꾸면 모델을 못 찾는다).
   */
  readonly attentionModel: string;
  /** Python exporter 가 모델과 함께 내보내는 배포 메타데이터. */
  readonly attentionModelMetadata: string;
}

export function attentionAssetPaths(base: string = ATTENTION_ASSET_BASE): AttentionAssetPaths {
  const root = base.replace(/\/+$/, "");
  return {
    visionWasmBase: `${root}/mediapipe/wasm`,
    faceLandmarkerModel: `${root}/mediapipe/face_landmarker.task`,
    onnxRuntimeWasmBase: `${root}/onnxruntime/`,
    attentionModel: `${root}/models/engagement.onnx`,
    attentionModelMetadata: `${root}/models/engagement.metadata.json`,
  };
}
