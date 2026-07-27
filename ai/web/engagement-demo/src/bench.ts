/**
 * Measures ONNX inference latency per execution provider.
 *
 * The product question is whether a model fits the demo's budget: the window
 * advances once per second, and MediaPipe needs part of that second too. Only
 * the model's own cost is timed here, with synthetic input of the declared
 * shape -- the feature pipeline is irrelevant to how long a forward pass takes,
 * and leaving it out lets any exported model be measured without wiring it into
 * the app first.
 *
 * A provider that fails to create a session is itself the result: it means an
 * operator in the graph is unsupported there.
 */

import type { InferenceSession } from "onnxruntime-web";

const PROVIDERS = ["webgpu", "wasm"] as const;
type Provider = (typeof PROVIDERS)[number];

/** Discards the first runs, which pay for shader compilation and allocation. */
const WARMUP_RUNS = 5;
const TIMED_RUNS = 30;

/** The demo advances its window once per second. */
const BUDGET_MS = 1000;

interface BenchTarget {
  readonly name: string;
  readonly modelUrl: string;
  readonly metadataUrl: string;
}

interface Metadata {
  readonly schema: string;
  readonly input_name: string;
  readonly input_shape: readonly (string | number)[];
  readonly output_name: string;
}

interface Measurement {
  readonly target: string;
  readonly schema: string;
  readonly provider: Provider;
  readonly ok: boolean;
  readonly detail?: string;
  readonly min?: number;
  readonly median?: number;
  readonly p95?: number;
  readonly mean?: number;
  readonly modelBytes?: number;
}

const TARGETS: readonly BenchTarget[] = [
  {
    name: "E0 Transformer",
    modelUrl: "/models/bench/e0.onnx",
    metadataUrl: "/models/bench/e0.metadata.json",
  },
  {
    name: "E1 ST-GCN",
    modelUrl: "/models/bench/e1.onnx",
    metadataUrl: "/models/bench/e1.metadata.json",
  },
];

function quantile(sorted: readonly number[], fraction: number): number {
  if (sorted.length === 0) return Number.NaN;
  const index = Math.min(sorted.length - 1, Math.floor(fraction * sorted.length));
  return sorted[index] ?? Number.NaN;
}

/** Resolves the declared shape to a concrete one, batching a single sample. */
function concreteShape(shape: readonly (string | number)[]): number[] {
  return shape.map((axis) => (typeof axis === "number" ? axis : 1));
}

async function measure(
  target: BenchTarget,
  metadata: Metadata,
  provider: Provider,
  modelBytes: number,
): Promise<Measurement> {
  const base = { target: target.name, schema: metadata.schema, provider, modelBytes };
  const ort = await import("onnxruntime-web/webgpu");
  let session: InferenceSession;
  try {
    session = await ort.InferenceSession.create(target.modelUrl, {
      executionProviders: [provider],
      graphOptimizationLevel: "all",
    });
  } catch (error) {
    // Almost always an unsupported operator for this backend.
    return { ...base, ok: false, detail: `session: ${String(error)}` };
  }

  const shape = concreteShape(metadata.input_shape);
  const elements = shape.reduce((product, axis) => product * axis, 1);
  const data = Float32Array.from({ length: elements }, (_, index) =>
    Math.sin(index * 0.01),
  );

  try {
    const feeds = { [metadata.input_name]: new ort.Tensor("float32", data, shape) };
    for (let run = 0; run < WARMUP_RUNS; run += 1) {
      await session.run(feeds);
    }
    const samples: number[] = [];
    for (let run = 0; run < TIMED_RUNS; run += 1) {
      const started = performance.now();
      const outputs = await session.run(feeds);
      // Reading the output forces the GPU queue to drain; without it a WebGPU
      // timing measures only how long enqueueing took.
      const output = outputs[metadata.output_name];
      if (output) await output.getData();
      samples.push(performance.now() - started);
    }
    samples.sort((left, right) => left - right);
    const mean = samples.reduce((sum, value) => sum + value, 0) / samples.length;
    return {
      ...base,
      ok: true,
      min: samples[0],
      median: quantile(samples, 0.5),
      p95: quantile(samples, 0.95),
      mean,
    };
  } catch (error) {
    return { ...base, ok: false, detail: `run: ${String(error)}` };
  } finally {
    await session.release?.();
  }
}

function render(rows: readonly Measurement[], target: HTMLElement): void {
  const cells = rows
    .map((row) => {
      const verdict = !row.ok
        ? '<span class="bad">실패</span>'
        : (row.median ?? Infinity) < BUDGET_MS
          ? '<span class="good">예산 내</span>'
          : '<span class="bad">예산 초과</span>';
      const number = (value: number | undefined) =>
        value === undefined ? "—" : value.toFixed(1);
      return `<tr>
        <td>${row.target}</td>
        <td><code>${row.schema}</code></td>
        <td>${row.provider}</td>
        <td>${row.modelBytes === undefined ? "—" : (row.modelBytes / 1048576).toFixed(2)}</td>
        <td>${number(row.min)}</td>
        <td><strong>${number(row.median)}</strong></td>
        <td>${number(row.p95)}</td>
        <td>${verdict}</td>
        <td class="detail">${row.detail ?? ""}</td>
      </tr>`;
    })
    .join("");
  target.innerHTML = `<table>
    <thead><tr>
      <th>모델</th><th>스키마</th><th>provider</th><th>MB</th>
      <th>min ms</th><th>median ms</th><th>p95 ms</th><th>1초 예산</th><th>비고</th>
    </tr></thead>
    <tbody>${cells}</tbody>
  </table>`;
}

export async function runBenchmark(output: HTMLElement, status: HTMLElement): Promise<void> {
  const rows: Measurement[] = [];
  status.textContent = `WebGPU 지원: ${"gpu" in navigator ? "예" : "아니오"}`;
  console.log(`BENCH webgpu_available=${"gpu" in navigator}`);

  for (const target of TARGETS) {
    let metadata: Metadata;
    let modelBytes = 0;
    try {
      const [metaResponse, modelResponse] = await Promise.all([
        fetch(target.metadataUrl),
        fetch(target.modelUrl, { method: "HEAD" }),
      ]);
      if (!metaResponse.ok) throw new Error(`metadata ${metaResponse.status}`);
      metadata = (await metaResponse.json()) as Metadata;
      modelBytes = Number(modelResponse.headers.get("content-length") ?? 0);
    } catch (error) {
      rows.push({
        target: target.name,
        schema: "?",
        provider: "wasm",
        ok: false,
        detail: `모델 파일 없음: ${String(error)}`,
      });
      render(rows, output);
      continue;
    }

    for (const provider of PROVIDERS) {
      status.textContent = `측정 중: ${target.name} / ${provider}`;
      const row = await measure(target, metadata, provider, modelBytes);
      rows.push(row);
      console.log(
        `BENCH ${row.target} | ${row.schema} | ${row.provider} | ok=${row.ok} | ` +
          `median=${row.median?.toFixed(1) ?? "-"}ms | p95=${row.p95?.toFixed(1) ?? "-"}ms | ` +
          `${row.detail ?? ""}`,
      );
      render(rows, output);
    }
  }
  status.textContent = "완료";
  console.log("BENCH done");
}
