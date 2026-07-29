export interface TrackVideoFrame {
  /** WebCodecs `VideoFrame.timestamp`, microseconds. */
  readonly timestamp: number;
  close(): void;
}
export type TrackFrameReadResult<TFrame extends TrackVideoFrame> =
  | { readonly done: false; readonly value: TFrame }
  | { readonly done: true; readonly value: undefined };

export interface TrackFramePumpOptions<TFrame extends TrackVideoFrame> {
  read(): Promise<TrackFrameReadResult<TFrame>>;
  readonly sampleIntervalMs: number;
  onFrame(frame: TFrame, timestampMs: number): void | Promise<void>;
  isStopped?: () => boolean;
}

/**
 * TrackProcessor가 내놓는 프레임 도착을 루프로 삼아 표본을 고른다.
 * Window의 visibility·animation frame·timer에 의존하지 않는다.
 */
export async function pumpTrackFrames<TFrame extends TrackVideoFrame>(
  options: TrackFramePumpOptions<TFrame>,
): Promise<void> {
  const { read, sampleIntervalMs, onFrame, isStopped = () => false } = options;
  let lastSampleAtMs = Number.NEGATIVE_INFINITY;

  while (!isStopped()) {
    const result = await read();
    if (result.done) return;
    const frame = result.value;
    if (isStopped()) {
      frame.close();
      return;
    }
    const timestampMs = frame.timestamp / 1_000;
    if (timestampMs - lastSampleAtMs < sampleIntervalMs) {
      frame.close();
      continue;
    }
    lastSampleAtMs = timestampMs;
    await onFrame(frame, timestampMs);
  }
}
