#!/usr/bin/env node
// attention 도메인이 런타임에 쓰는 정적 자산을 public/attention/ 으로 모은다.
//
// Vite 데모는 MediaPipe wasm 과 face landmarker 모델을 CDN 에서 받았다. 서비스는 Next.js 가
// 같은 오리진에서 직접 서빙해야 하므로(빌드에 포함되고 런타임에 404 가 나면 안 된다) 설치된
// 패키지에서 복사한다. 경로 계약은 src/domains/attention/infrastructure/attentionAssets.ts 다.
//
// prebuild/predev 에서 자동 실행된다. 수동 실행: npm run sync:attention-assets
//
// 결과는 100MB 가 넘지만(onnxruntime 이 브라우저 기능 감지에 따라 wasm 변종을 골라 받는다)
// 커밋하지 않고 빌드 때마다 생성하며, 브라우저는 그중 하나만 내려받아 캐싱한다.

import { copyFile, mkdir, readdir, stat } from "node:fs/promises";
import { createWriteStream } from "node:fs";
import { pipeline } from "node:stream/promises";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

import { syncAttentionModelArtifact } from "./attention-model-artifact.mjs";

const feRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const assetRoot = join(feRoot, "public", "attention");
const modelSource = join(feRoot, "assets", "attention-model", "v1");

const VISION_WASM_SOURCE = join(feRoot, "node_modules", "@mediapipe", "tasks-vision", "wasm");
const ORT_DIST_SOURCE = join(feRoot, "node_modules", "onnxruntime-web", "dist");

// @mediapipe/tasks-vision 과 같은 버전의 FaceLandmarker 모델.
const FACE_LANDMARKER_URL =
  "https://storage.googleapis.com/mediapipe-models/face_landmarker/" +
  "face_landmarker/float16/1/face_landmarker.task";

async function exists(path) {
  try {
    await stat(path);
    return true;
  } catch {
    return false;
  }
}

/** 이름이 조건에 맞는 파일만 대상 디렉터리로 복사한다. */
async function copyMatching(source, target, matches) {
  if (!(await exists(source))) {
    throw new Error(
      `자산 원본을 찾을 수 없습니다: ${source}\nnpm install 을 먼저 실행하세요.`,
    );
  }
  await mkdir(target, { recursive: true });
  const entries = await readdir(source, { withFileTypes: true });
  const files = entries.filter((entry) => entry.isFile() && matches(entry.name));
  if (files.length === 0) {
    throw new Error(`복사할 자산이 없습니다: ${source}`);
  }
  await Promise.all(files.map((file) => copyFile(join(source, file.name), join(target, file.name))));
  return files.length;
}

/**
 * FaceLandmarker 모델을 내려받는다. 이미 있으면 건너뛰고, 네트워크가 없으면 경고만 남긴다.
 * 여기서 실패해도 설치·빌드를 막지 않고, 런타임에는 모델 준비 실패 상태로 안전하게 떨어진다.
 */
async function downloadFaceLandmarker(target) {
  if (await exists(target)) return "cached";
  try {
    const response = await fetch(FACE_LANDMARKER_URL);
    if (!response.ok || !response.body) {
      throw new Error(`HTTP ${response.status}`);
    }
    await mkdir(dirname(target), { recursive: true });
    await pipeline(response.body, createWriteStream(target));
    return "downloaded";
  } catch (error) {
    console.warn(
      `[attention] FaceLandmarker 모델을 내려받지 못했습니다: ${error instanceof Error ? error.message : error}`,
    );
    console.warn(`[attention] 네트워크가 되는 환경에서 다시 실행하거나 직접 내려받아 주세요.`);
    console.warn(`[attention]   ${FACE_LANDMARKER_URL}`);
    console.warn(`[attention]   → ${target}`);
    return "missing";
  }
}

await syncAttentionModelArtifact({
  sourceDir: modelSource,
  targetDir: join(assetRoot, "models"),
  packageJsonPath: join(feRoot, "package.json"),
});
console.log("[attention] 검증된 참여도 ONNX 모델과 배포 메타데이터를 복사했습니다.");

// FilesetResolver 가 SIMD 지원 여부에 따라 골라 받으므로 wasm 디렉터리를 그대로 옮긴다.
const visionWasmCount = await copyMatching(
  VISION_WASM_SOURCE,
  join(assetRoot, "mediapipe", "wasm"),
  (name) => name.endsWith(".wasm") || name.endsWith(".js"),
);
console.log(`[attention] MediaPipe wasm 자산 ${visionWasmCount}개를 복사했습니다.`);

// ort.env.wasm.wasmPaths 로 런타임에 받아가는 파일은 ort-wasm-* 뿐이다. ort.*.mjs 는 번들러가
// 직접 묶으므로 복사하면 90MB 넘게 낭비된다.
const ortCount = await copyMatching(
  ORT_DIST_SOURCE,
  join(assetRoot, "onnxruntime"),
  (name) => name.startsWith("ort-wasm-") && (name.endsWith(".wasm") || name.endsWith(".mjs")),
);
console.log(`[attention] onnxruntime-web wasm 자산 ${ortCount}개를 복사했습니다.`);

const landmarkerTarget = join(assetRoot, "mediapipe", "face_landmarker.task");
const landmarkerResult = await downloadFaceLandmarker(landmarkerTarget);
console.log(`[attention] FaceLandmarker 모델: ${landmarkerResult}`);
