import { ATTENTION_DETECTION_CONFIG } from "./attentionDetectionConfig";
import {
  ATTENTION_TOKEN_FEATURE_COUNT,
  RAW_ATTENTION_FEATURE_COUNT,
} from "./attentionFeatureSchema";

interface TimedFrameFeatures {
  readonly timestampMs: number;
  readonly values: Float32Array | null;
}

export interface WindowOptions {
  readonly windowMs: number;
  readonly expectedFrameCount: number;
  readonly segmentCount: number;
  readonly minimumValidFrameRatio: number;
  readonly minimumValidFrames: number;
}

/**
 * 10초 창을 20세그먼트로 나눠 세그먼트별 mean/std 를 잇는 20×98 토큰 생성기.
 * `ai/web/engagement-demo/src/rolling-window.ts` 에서 이식했다.
 */

const DEFAULT_OPTIONS: WindowOptions = {
  windowMs: ATTENTION_DETECTION_CONFIG.windowMs,
  expectedFrameCount: ATTENTION_DETECTION_CONFIG.expectedFrameCount,
  segmentCount: ATTENTION_DETECTION_CONFIG.segmentCount,
  minimumValidFrameRatio: ATTENTION_DETECTION_CONFIG.minimumValidFrameRatio,
  minimumValidFrames: ATTENTION_DETECTION_CONFIG.minimumValidFramesPerSegment,
};

export type WindowEvaluation =
  | { readonly kind: "pending" }
  | { readonly kind: "unmeasurable" }
  | { readonly kind: "measurable"; readonly tokens: Float32Array };

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
    if (values !== null && values.length !== RAW_ATTENTION_FEATURE_COUNT) {
      throw new Error(`프레임 특징은 ${RAW_ATTENTION_FEATURE_COUNT}차원이어야 합니다.`);
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

  evaluate(nowMs: number): WindowEvaluation {
    if (this.progress(nowMs) < 1) return { kind: "pending" };
    const start = nowMs - this.options.windowMs;
    const segmentMs = this.options.windowMs / this.options.segmentCount;
    const buckets: Float32Array[][] = Array.from(
      { length: this.options.segmentCount },
      () => [],
    );
    for (const frame of this.frames) {
      // 창은 반열린 구간 [start, nowMs) 다. `>=` 를 `>` 로 바꾸면 정확히 nowMs 인 경계
      // 프레임이 마지막 세그먼트에 더해져 그 세그먼트만 6프레임이 되고, 20개 세그먼트가
      // 같은 500ms 를 덮는다는 학습 계약(`mediapipe_98_v1`)이 깨진다. 경계 프레임은 다음
      // 창 몫이다. rollingFeatureWindow.test.ts 가 이 규약을 고정한다.
      if (frame.timestampMs < start || frame.timestampMs >= nowMs || frame.values === null) continue;
      const index = Math.min(
        Math.floor((frame.timestampMs - start) / segmentMs),
        this.options.segmentCount - 1,
      );
      buckets[index]?.push(frame.values);
    }
    const validFrameCount = buckets.reduce((total, bucket) => total + bucket.length, 0);
    const minimumValidFrameCount = Math.ceil(
      this.options.expectedFrameCount * this.options.minimumValidFrameRatio,
    );
    if (validFrameCount < minimumValidFrameCount) return { kind: "unmeasurable" };
    if (buckets.some((bucket) => bucket.length < this.options.minimumValidFrames)) {
      return { kind: "unmeasurable" };
    }

    const output = new Float32Array(
      this.options.segmentCount * ATTENTION_TOKEN_FEATURE_COUNT,
    );
    buckets.forEach((bucket, segment) => {
      const tokenOffset = segment * ATTENTION_TOKEN_FEATURE_COUNT;
      for (let feature = 0; feature < RAW_ATTENTION_FEATURE_COUNT; feature += 1) {
        let sum = 0;
        for (const frame of bucket) sum += frame[feature] ?? 0;
        const mean = sum / bucket.length;
        let squaredDifference = 0;
        for (const frame of bucket) {
          const difference = (frame[feature] ?? 0) - mean;
          squaredDifference += difference * difference;
        }
        output[tokenOffset + feature] = mean;
        output[tokenOffset + RAW_ATTENTION_FEATURE_COUNT + feature] = Math.sqrt(
          squaredDifference / bucket.length,
        );
      }
    });
    return { kind: "measurable", tokens: output };
  }

  tokens(nowMs: number): Float32Array | null {
    const evaluation = this.evaluate(nowMs);
    return evaluation.kind === "measurable" ? evaluation.tokens : null;
  }

  clear(): void {
    this.frames.length = 0;
    this.startedAtMs = null;
  }
}
