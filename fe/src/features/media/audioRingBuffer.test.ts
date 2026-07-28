import { describe, expect, it } from "vitest";

import {
  AUDIO_CLIP_WINDOW_MS,
  AudioRingBuffer,
  MIN_UPLOADABLE_MS,
  canUploadClip,
} from "./audioRingBuffer";

const mimeType = "audio/webm;codecs=opus";

/** size 바이트짜리 더미 blob. 내용은 검증에 쓰지 않고 크기 산술만 확인한다. */
const blobOf = (size: number): Blob => new Blob([new Uint8Array(size)], { type: mimeType });

const HEADER_SIZE = 40;

/** 가짜 시계와 작은 창(10초)으로 버퍼를 만든다. 테스트 가독성을 위해 창을 줄인다. */
const createBuffer = () => {
  const clock = { nowMs: 0 };
  const buffer = new AudioRingBuffer({
    windowMs: 10_000,
    gapToleranceMs: 500,
    now: () => clock.nowMs,
  });
  return { buffer, clock };
};

describe("AudioRingBuffer", () => {
  it("헤더가 없거나 조각이 없으면 snapshot은 null이다", () => {
    const { buffer, clock } = createBuffer();
    expect(buffer.snapshot()).toBeNull();

    buffer.setHeader(blobOf(HEADER_SIZE));
    expect(buffer.snapshot()).toBeNull(); // 헤더만 있고 오디오가 없다

    clock.nowMs = 1_000;
    buffer.append(blobOf(100), 0, 1_000);
    buffer.reset();
    expect(buffer.snapshot()).toBeNull(); // reset은 헤더까지 비운다
  });

  it("연속 조각을 헤더와 함께 하나의 클립으로 스냅샷한다", () => {
    const { buffer, clock } = createBuffer();
    buffer.setHeader(blobOf(HEADER_SIZE));
    buffer.append(blobOf(100), 0, 1_000);
    buffer.append(blobOf(100), 1_000, 2_000);
    buffer.append(blobOf(100), 2_000, 3_000);
    clock.nowMs = 3_000;

    const clip = buffer.snapshot();

    expect(clip).not.toBeNull();
    expect(clip?.blob.size).toBe(HEADER_SIZE + 300);
    expect(clip?.mimeType).toBe(mimeType);
    expect(clip?.capturedFromMs).toBe(0);
    expect(clip?.capturedToMs).toBe(3_000);
    expect(clip?.durationMs).toBe(3_000);
    expect(clip?.segments).toEqual([{ fromMs: 0, toMs: 3_000 }]);
  });

  it("창 밖으로 밀려난 조각은 만료되고 헤더는 유지된다", () => {
    const { buffer, clock } = createBuffer();
    buffer.setHeader(blobOf(HEADER_SIZE));
    buffer.append(blobOf(100), 0, 1_000);
    buffer.append(blobOf(100), 1_000, 2_000);

    // 창(10초) 기준: cutoff = 11500 - 10000 = 1500 → endMs 1000짜리 첫 조각만 만료된다.
    clock.nowMs = 11_500;
    expect(buffer.availableMs()).toBe(1_000);

    const clip = buffer.snapshot();
    expect(clip?.blob.size).toBe(HEADER_SIZE + 100);
    expect(clip?.capturedFromMs).toBe(1_000);
    expect(clip?.segments).toEqual([{ fromMs: 1_000, toMs: 2_000 }]);
  });

  it("모든 조각이 만료되면 snapshot은 null이고 availableMs는 0이다", () => {
    const { buffer, clock } = createBuffer();
    buffer.setHeader(blobOf(HEADER_SIZE));
    buffer.append(blobOf(100), 0, 1_000);

    clock.nowMs = 60_000;
    expect(buffer.availableMs()).toBe(0);
    expect(buffer.snapshot()).toBeNull();
  });

  it("허용 오차보다 큰 공백(음소거)은 별도 캡처 구간으로 나뉘고 durationMs에서 빠진다", () => {
    const { buffer, clock } = createBuffer();
    buffer.setHeader(blobOf(HEADER_SIZE));
    buffer.append(blobOf(100), 0, 1_000);
    buffer.append(blobOf(100), 1_000, 2_000);
    // 2초~7초 음소거(공백 5초) 후 재개
    buffer.append(blobOf(100), 7_000, 8_000);
    clock.nowMs = 8_000;

    const clip = buffer.snapshot();

    expect(clip?.segments).toEqual([
      { fromMs: 0, toMs: 2_000 },
      { fromMs: 7_000, toMs: 8_000 },
    ]);
    expect(clip?.durationMs).toBe(3_000);
    expect(clip?.capturedFromMs).toBe(0);
    expect(clip?.capturedToMs).toBe(8_000);
  });

  it("허용 오차 이내의 타이밍 지터는 같은 캡처 구간으로 이어붙인다", () => {
    const { buffer, clock } = createBuffer();
    buffer.setHeader(blobOf(HEADER_SIZE));
    buffer.append(blobOf(100), 0, 1_000);
    buffer.append(blobOf(100), 1_400, 2_400); // 공백 400ms ≤ 500ms
    clock.nowMs = 2_400;

    expect(buffer.snapshot()?.segments).toEqual([{ fromMs: 0, toMs: 2_400 }]);
  });

  it("빈 조각과 시간이 역행하는 조각은 무시한다", () => {
    const { buffer, clock } = createBuffer();
    buffer.setHeader(blobOf(HEADER_SIZE));
    buffer.append(blobOf(0), 0, 1_000); // 빈 blob
    buffer.append(blobOf(100), 2_000, 2_000); // endMs <= startMs
    buffer.append(blobOf(100), 3_000, 2_500); // 역행
    clock.nowMs = 3_000;

    expect(buffer.availableMs()).toBe(0);
    expect(buffer.snapshot()).toBeNull();
  });

  it("수업 시작 직후(1분 미만 녹음)에는 업로드 기준에 미달한다", () => {
    // 창(5분)보다 짧게 쌓인 것 자체는 스냅샷을 막지 않는다 — 보낼지 말지는
    // canUploadClip 판정이 담당하고, 1분 미만이면 업로드하지 않는다.
    expect(canUploadClip(0)).toBe(false);
    expect(canUploadClip(59_999)).toBe(false);
    expect(canUploadClip(MIN_UPLOADABLE_MS)).toBe(true);
    expect(canUploadClip(180_000)).toBe(true);
  });

  it("60분을 수집해도 버퍼가 창 크기 이상으로 커지지 않는다", () => {
    // 실제 운영 값(창 300초, 1초 timeslice, 32kbps Opus ≈ 4KB/s)으로 60분을 시뮬레이션한다.
    const clock = { nowMs: 0 };
    const windowMs = AUDIO_CLIP_WINDOW_MS;
    const buffer = new AudioRingBuffer({ windowMs, now: () => clock.nowMs });
    buffer.setHeader(blobOf(HEADER_SIZE));

    const chunkMs = 1_000;
    const chunkBytes = 4_000;
    const totalChunks = (60 * 60_000) / chunkMs;
    // 만료는 조각 단위라 창을 최대 한 조각만큼 넘을 수 있다.
    const maxRetainedMs = windowMs + chunkMs;
    let peakAvailableMs = 0;
    let peakBlobBytes = 0;

    for (let i = 0; i < totalChunks; i += 1) {
      const startMs = i * chunkMs;
      clock.nowMs = startMs + chunkMs;
      buffer.append(blobOf(chunkBytes), startMs, clock.nowMs);
      peakAvailableMs = Math.max(peakAvailableMs, buffer.availableMs());

      // blob 조립은 비싸므로 10분마다만 확인한다.
      if (i % 600 === 0) {
        peakBlobBytes = Math.max(peakBlobBytes, buffer.snapshot()?.blob.size ?? 0);
      }
    }
    peakBlobBytes = Math.max(peakBlobBytes, buffer.snapshot()?.blob.size ?? 0);

    expect(peakAvailableMs).toBeLessThanOrEqual(maxRetainedMs);
    expect(peakBlobBytes).toBeLessThanOrEqual(HEADER_SIZE + (maxRetainedMs / chunkMs) * chunkBytes);
    // 만료가 없었다면 60분 = 14.4MB. 창 유지가 실제로 동작하는지 절대값으로도 못 박는다.
    expect(peakBlobBytes).toBeLessThan(1_500_000);
  });

  it("snapshot은 버퍼를 비우지 않는다 — 연속 두 번 호출해도 같은 클립을 만든다", () => {
    const { buffer, clock } = createBuffer();
    buffer.setHeader(blobOf(HEADER_SIZE));
    buffer.append(blobOf(100), 0, 1_000);
    clock.nowMs = 1_000;

    const first = buffer.snapshot();
    const second = buffer.snapshot();

    expect(first?.blob.size).toBe(HEADER_SIZE + 100);
    expect(second?.blob.size).toBe(HEADER_SIZE + 100);
    expect(second?.segments).toEqual(first?.segments);
  });
});
