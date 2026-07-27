import type { TimedFrameFeatures, WindowOptions } from "./frameContracts";
import { RAW_FEATURE_COUNT, TOKEN_FEATURE_COUNT } from "./frameFeatures";

/**
 * 10초 창을 20세그먼트로 나눠 세그먼트별 mean/std 를 잇는 20×98 토큰 생성기.
 * `ai/web/engagement-demo/src/rolling-window.ts` 에서 이식했다.
 */

const DEFAULT_OPTIONS: WindowOptions = {
  windowMs: 10_000,
  segmentCount: 20,
  minimumValidFrames: 3,
};

export class RollingFeatureWindow {
  readonly options: WindowOptions;
  private readonly frames: TimedFrameFeatures[] = [];
  private startedAtMs: number | null = null;

  constructor(options: Partial<WindowOptions> = {}) {
    this.options = { ...DEFAULT_OPTIONS, ...options };
  }

  get size(): number {
    return this.frames.length;
  }

  add(timestampMs: number, values: Float32Array | null): void {
    if (values !== null && values.length !== RAW_FEATURE_COUNT) {
      throw new Error(`프레임 특징은 ${RAW_FEATURE_COUNT}차원이어야 합니다.`);
    }
    this.startedAtMs ??= timestampMs;
    this.frames.push({ timestampMs, values });
    const cutoff = timestampMs - this.options.windowMs;
    while (this.frames[0] && this.frames[0].timestampMs < cutoff) this.frames.shift();
  }

  progress(nowMs: number): number {
    if (this.startedAtMs === null) return 0;
    return Math.min(1, Math.max(0, (nowMs - this.startedAtMs) / this.options.windowMs));
  }

  tokens(nowMs: number): Float32Array | null {
    if (this.progress(nowMs) < 1) return null;
    const start = nowMs - this.options.windowMs;
    const segmentMs = this.options.windowMs / this.options.segmentCount;
    const buckets: Float32Array[][] = Array.from(
      { length: this.options.segmentCount },
      () => [],
    );
    for (const frame of this.frames) {
      if (frame.timestampMs < start || frame.timestampMs >= nowMs || frame.values === null) continue;
      const index = Math.min(
        Math.floor((frame.timestampMs - start) / segmentMs),
        this.options.segmentCount - 1,
      );
      buckets[index]?.push(frame.values);
    }
    if (buckets.some((bucket) => bucket.length < this.options.minimumValidFrames)) return null;

    const output = new Float32Array(this.options.segmentCount * TOKEN_FEATURE_COUNT);
    buckets.forEach((bucket, segment) => {
      const tokenOffset = segment * TOKEN_FEATURE_COUNT;
      for (let feature = 0; feature < RAW_FEATURE_COUNT; feature += 1) {
        let sum = 0;
        for (const frame of bucket) sum += frame[feature] ?? 0;
        const mean = sum / bucket.length;
        let squaredDifference = 0;
        for (const frame of bucket) {
          const difference = (frame[feature] ?? 0) - mean;
          squaredDifference += difference * difference;
        }
        output[tokenOffset + feature] = mean;
        output[tokenOffset + RAW_FEATURE_COUNT + feature] = Math.sqrt(
          squaredDifference / bucket.length,
        );
      }
    });
    return output;
  }

  clear(): void {
    this.frames.length = 0;
    this.startedAtMs = null;
  }
}
