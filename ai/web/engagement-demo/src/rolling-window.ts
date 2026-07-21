import type { TimedFrameFeatures, WindowOptions } from "./contracts";
import { RAW_FEATURE_COUNT, TOKEN_FEATURE_COUNT } from "./features";

const DEFAULT_OPTIONS: WindowOptions = {
  windowMs: 10_000,
  segmentCount: 20,
  minimumValidFrames: 3,
};

export class RollingFeatureWindow {
  readonly options: WindowOptions;
  private readonly frames: TimedFrameFeatures[] = [];

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
    this.frames.push({ timestampMs, values });
    const cutoff = timestampMs - this.options.windowMs;
    while (this.frames[0] && this.frames[0].timestampMs < cutoff) this.frames.shift();
  }

  progress(nowMs: number): number {
    const first = this.frames[0];
    if (!first) return 0;
    return Math.min(1, Math.max(0, (nowMs - first.timestampMs) / this.options.windowMs));
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
  }
}
