import type { InferenceSession } from "onnxruntime-web";

import {
  ATTENTION_LABELS,
  type AttentionPrediction,
} from "../domain/attentionPrediction";
import { attentionAssetPaths, type AttentionAssetPaths } from "./attentionAssets";
import { SCHEMA_NAME } from "./frameFeatures";

/**
 * onnxruntime-web 추론 어댑터. `ai/web/engagement-demo/src/model.ts` 에서 이식했다.
 *
 * 데모와 달리 모델·wasm 을 CDN 대신 `public/attention/` 에서 받고, 결과를 도메인 타입
 * `AttentionPrediction` 으로 돌려준다. Worker 안에서만 생성한다.
 */

export interface DeploymentMetadata {
  schema: typeof SCHEMA_NAME;
  input_name: string;
  input_shape: readonly ["batch", 20, 98];
  output_name: string;
  labels: typeof ATTENTION_LABELS;
  window_seconds: 10;
  segment_count: 20;
  sample_fps: 10;
}

export interface AttentionModel {
  readonly metadata: DeploymentMetadata;
  predict(tokens: Float32Array): Promise<AttentionPrediction>;
  dispose(): Promise<void>;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

function isExactArray(value: unknown, expected: readonly unknown[]): boolean {
  return Array.isArray(value) &&
    value.length === expected.length &&
    value.every((item, index) => item === expected[index]);
}

export function validateMetadata(value: unknown): DeploymentMetadata {
  if (!isRecord(value)) throw new Error("모델 메타데이터 형식이 올바르지 않습니다.");
  if (value.schema !== SCHEMA_NAME) {
    throw new Error(`지원하지 않는 특징 스키마입니다: ${String(value.schema)}`);
  }
  if (!isExactArray(value.input_shape, ["batch", 20, 98])) {
    throw new Error("모델 입력 shape은 [batch, 20, 98]이어야 합니다.");
  }
  if (!isExactArray(value.labels, ATTENTION_LABELS)) {
    throw new Error("모델 클래스 순서가 학습 계약과 다릅니다.");
  }
  if (
    typeof value.input_name !== "string" ||
    typeof value.output_name !== "string" ||
    value.window_seconds !== 10 ||
    value.segment_count !== 20 ||
    value.sample_fps !== 10
  ) {
    throw new Error("모델 시간 창 메타데이터가 mediapipe_98_v1과 다릅니다.");
  }
  return value as unknown as DeploymentMetadata;
}

export function softmax(logits: Float32Array): Float32Array {
  if (logits.length !== ATTENTION_LABELS.length) {
    throw new Error("모델은 4개 logit을 출력해야 합니다.");
  }
  const maximum = Math.max(...logits);
  const exponentials = Array.from(logits, (value) => Math.exp(value - maximum));
  const total = exponentials.reduce((sum, value) => sum + value, 0);
  return Float32Array.from(exponentials, (value) => value / total);
}

/** onnxruntime-web 로더. 테스트에서 가짜 런타임으로 대체한다. */
export type OnnxRuntimeLoader = () => Promise<typeof import("onnxruntime-web/webgpu")>;

const loadWebGpuRuntime: OnnxRuntimeLoader = () => import("onnxruntime-web/webgpu");

export interface CreateAttentionModelOptions {
  readonly paths?: AttentionAssetPaths;
  readonly loadRuntime?: OnnxRuntimeLoader;
}

/**
 * WebGPU 로 세션을 열고, 미지원 환경에서는 WASM 백엔드로 폴백한다.
 * 두 백엔드 모두 wasm 바이너리를 같은 오리진의 `public/attention/onnxruntime/` 에서 받는다.
 */
async function createSession(
  modelUrl: string,
  wasmPaths: string,
  loadRuntime: OnnxRuntimeLoader,
): Promise<{ session: InferenceSession; ort: typeof import("onnxruntime-web/webgpu") }> {
  const ort = await loadRuntime();
  ort.env.wasm.wasmPaths = wasmPaths;
  try {
    const session = await ort.InferenceSession.create(modelUrl, {
      executionProviders: ["webgpu"],
      graphOptimizationLevel: "all",
    });
    return { session, ort };
  } catch (webGpuError) {
    console.info("WebGPU unavailable; falling back to WASM.", webGpuError);
    const session = await ort.InferenceSession.create(modelUrl, {
      executionProviders: ["wasm"],
      graphOptimizationLevel: "all",
    });
    return { session, ort };
  }
}

export async function createAttentionModel(
  options: CreateAttentionModelOptions = {},
): Promise<AttentionModel> {
  const { paths = attentionAssetPaths(), loadRuntime = loadWebGpuRuntime } = options;
  const response = await fetch(paths.attentionModelMetadata);
  if (!response.ok || !response.headers.get("content-type")?.includes("application/json")) {
    throw new Error("학습된 모델이 없습니다. Python export 명령을 먼저 실행하세요.");
  }
  const metadata = validateMetadata(await response.json());
  const { session, ort } = await createSession(
    paths.attentionModel,
    paths.onnxRuntimeWasmBase,
    loadRuntime,
  );
  return {
    metadata,
    async predict(tokens: Float32Array): Promise<AttentionPrediction> {
      if (tokens.length !== 20 * 98) throw new Error("ONNX 입력은 20×98 토큰이어야 합니다.");
      const input = new ort.Tensor("float32", tokens, [1, 20, 98]);
      const outputs = await session.run({ [metadata.input_name]: input });
      const output = outputs[metadata.output_name];
      if (!output || !(output.data instanceof Float32Array)) {
        throw new Error("ONNX 모델이 float32 logits을 반환하지 않았습니다.");
      }
      const probabilities = softmax(output.data);
      let bestIndex = 0;
      for (let index = 1; index < probabilities.length; index += 1) {
        if ((probabilities[index] ?? 0) > (probabilities[bestIndex] ?? 0)) bestIndex = index;
      }
      return {
        label: ATTENTION_LABELS[bestIndex] ?? ATTENTION_LABELS[0],
        probabilities: Array.from(probabilities),
      };
    },
    async dispose(): Promise<void> {
      await session.release();
    },
  };
}
