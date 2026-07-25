import type { InferenceSession } from "onnxruntime-web";

import { SCHEMA_NAME } from "./features";

const LABELS = ["Not-Engaged", "Barely-Engaged", "Engaged", "Highly-Engaged"] as const;

export interface DeploymentMetadata {
  schema: typeof SCHEMA_NAME;
  input_name: string;
  input_shape: readonly ["batch", 20, 98];
  output_name: string;
  labels: typeof LABELS;
  window_seconds: 10;
  segment_count: 20;
  sample_fps: 10;
}

export interface Prediction {
  label: (typeof LABELS)[number];
  probabilities: Float32Array;
}

export interface EngagementModel {
  readonly metadata: DeploymentMetadata;
  predict(tokens: Float32Array): Promise<Prediction>;
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
  if (!isExactArray(value.labels, LABELS)) {
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
  if (logits.length !== LABELS.length) throw new Error("모델은 4개 logit을 출력해야 합니다.");
  const maximum = Math.max(...logits);
  const exponentials = Array.from(logits, (value) => Math.exp(value - maximum));
  const total = exponentials.reduce((sum, value) => sum + value, 0);
  return Float32Array.from(exponentials, (value) => value / total);
}

async function createSession(
  modelUrl: string,
): Promise<{ session: InferenceSession; ort: typeof import("onnxruntime-web/webgpu") }> {
  const ort = await import("onnxruntime-web/webgpu");
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

export async function createEngagementModel(
  modelUrl = "/models/engagement.onnx",
  metadataUrl = "/models/engagement.metadata.json",
): Promise<EngagementModel> {
  const response = await fetch(metadataUrl);
  if (!response.ok || !response.headers.get("content-type")?.includes("application/json")) {
    throw new Error("학습된 모델이 없습니다. Python export 명령을 먼저 실행하세요.");
  }
  const metadata = validateMetadata(await response.json());
  const { session, ort } = await createSession(modelUrl);
  return {
    metadata,
    async predict(tokens: Float32Array): Promise<Prediction> {
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
      return { label: LABELS[bestIndex] ?? LABELS[0], probabilities };
    },
    async dispose(): Promise<void> {
      await session.release();
    },
  };
}
