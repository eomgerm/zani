import {
  DrawingUtils,
  FaceLandmarker,
  FilesetResolver,
  type FaceLandmarkerResult,
} from "@mediapipe/tasks-vision";

import type { FrameLandmarkerValues } from "./contracts";
import { columnMajorTransformToRowMajor } from "./matrix";

const VERSION = "0.10.35";
const WASM_URL = `https://cdn.jsdelivr.net/npm/@mediapipe/tasks-vision@${VERSION}/wasm`;
const MODEL_URL =
  "https://storage.googleapis.com/mediapipe-models/face_landmarker/" +
  "face_landmarker/float16/1/face_landmarker.task";

export interface BrowserFaceLandmarker {
  detect(video: HTMLVideoElement, timestampMs: number): FrameLandmarkerValues | null;
  draw(result: FaceLandmarkerResult | null, canvas: HTMLCanvasElement): void;
  close(): void;
}

export async function createBrowserFaceLandmarker(): Promise<BrowserFaceLandmarker> {
  const fileset = await FilesetResolver.forVisionTasks(WASM_URL);
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
      baseOptions: { modelAssetPath: MODEL_URL, delegate: "GPU" },
    });
  } catch (gpuError) {
    console.info("MediaPipe GPU unavailable; falling back to CPU.", gpuError);
    landmarker = await FaceLandmarker.createFromOptions(fileset, {
      ...commonOptions,
      baseOptions: { modelAssetPath: MODEL_URL, delegate: "CPU" },
    });
  }
  let lastResult: FaceLandmarkerResult | null = null;
  return {
    detect(video: HTMLVideoElement, timestampMs: number): FrameLandmarkerValues | null {
      lastResult = landmarker.detectForVideo(video, timestampMs);
      const landmarks = lastResult.faceLandmarks[0];
      const matrix = lastResult.facialTransformationMatrixes[0];
      if (!landmarks || !matrix || matrix.rows !== 4 || matrix.columns !== 4) return null;
      const categories = lastResult.faceBlendshapes[0]?.categories ?? [];
      return {
        landmarks,
        transform: columnMajorTransformToRowMajor(matrix.data),
        blendshapes: new Map(categories.map((category) => [category.categoryName, category.score])),
      };
    },
    draw(result: FaceLandmarkerResult | null, canvas: HTMLCanvasElement): void {
      const context = canvas.getContext("2d");
      if (!context) return;
      context.clearRect(0, 0, canvas.width, canvas.height);
      const landmarks = result?.faceLandmarks[0] ?? lastResult?.faceLandmarks[0];
      if (!landmarks) return;
      const drawing = new DrawingUtils(context);
      drawing.drawConnectors(landmarks, FaceLandmarker.FACE_LANDMARKS_TESSELATION, {
        color: "rgba(94, 234, 212, 0.42)",
        lineWidth: 0.45,
      });
      drawing.drawConnectors(landmarks, FaceLandmarker.FACE_LANDMARKS_FACE_OVAL, {
        color: "rgba(255, 255, 255, 0.8)",
        lineWidth: 1.2,
      });
    },
    close(): void {
      landmarker.close();
      lastResult = null;
    },
  };
}
