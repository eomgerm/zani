/**
 * 강사 마이크 오디오의 최근 N초 롤링 버퍼 (브라우저 메모리 전용).
 *
 * MediaRecorder 가 timeslice 마다 내놓는 WebM 조각을 벽시계 시각과 함께 보관하고,
 * 오래된 조각을 만료시키며, 서버가 클립을 요청하면 [헤더 + 생존 조각]을 하나의
 * Blob 으로 스냅샷한다. 디스크·스토리지에 저장하지 않는다.
 *
 * 정밀도 제약: 만료는 조각(≈timeslice) 단위라 창 경계가 최대 조각 길이만큼
 * 과거를 더 포함할 수 있다. 잘린 클립의 Cluster timecode 는 0에서 시작하지
 * 않지만, 서버가 전사용으로 트랜스코딩하면서 정규화되므로 문제되지 않는다.
 * (Chrome 은 timeslice 조각을 Cluster 경계에서 시작한다. 경계가 어긋난 조각이
 * 섞여도 디코더는 다음 Cluster ID 로 재동기화한다.)
 */

/** 서버 계약(windowSec=300)과 함께 바뀌어야 하는 롤링 창 길이. */
export const AUDIO_CLIP_WINDOW_MS = 300_000;

/**
 * 업로드할 가치가 있는 최소 캡처 시간. 수업 시작 직후처럼 이보다 짧게 쌓였을 때
 * 요청이 오면 업로드하지 않고 INSUFFICIENT_AUDIO(가용량 포함)로 보고한다.
 * 서버 검증값(audio-clip.min-uploadable)과 함께 바뀌어야 한다.
 */
export const MIN_UPLOADABLE_MS = 60_000;

/** 가용 캡처 시간이 업로드 기준을 넘는지. 경계값(정확히 60초)은 업로드한다. */
export const canUploadClip = (availableMs: number): boolean =>
  availableMs >= MIN_UPLOADABLE_MS;

/**
 * 이 간격(ms) 이하로 이어진 조각은 같은 연속 캡처 구간으로 본다.
 * dataavailable 타이밍 지터를 흡수하기 위한 값으로, 음소거로 생기는 공백
 * (일시정지 → 재개)은 이 값보다 훨씬 길어 별도 구간으로 나뉜다.
 */
export const SEGMENT_GAP_TOLERANCE_MS = 500;

/** 실제로 캡처가 이뤄진 연속 구간(벽시계 epoch ms). 음소거 공백의 반대 개념. */
export interface CapturedSegment {
  readonly fromMs: number;
  readonly toMs: number;
}

/** 서버 업로드 한 건에 해당하는 클립 스냅샷. */
export interface AudioClipSnapshot {
  readonly blob: Blob;
  readonly mimeType: string;
  /** 클립에 포함된 가장 오래된 오디오의 벽시계 시각(epoch ms). */
  readonly capturedFromMs: number;
  /** 클립에 포함된 가장 최신 오디오의 벽시계 시각(epoch ms). */
  readonly capturedToMs: number;
  /** 실제 캡처된 시간 합(ms). 음소거 공백은 포함하지 않는다. */
  readonly durationMs: number;
  /** 캡처 구간 목록. 항상 1개 이상이며 시간 오름차순이다. */
  readonly segments: readonly CapturedSegment[];
}

export interface AudioRingBufferOptions {
  readonly windowMs?: number;
  readonly gapToleranceMs?: number;
  /** 시간 소스 주입점. 테스트에서 가짜 시계를 쓴다. */
  readonly now?: () => number;
}

interface BufferedChunk {
  readonly blob: Blob;
  readonly startMs: number;
  readonly endMs: number;
}

export class AudioRingBuffer {
  private readonly windowMs: number;

  private readonly gapToleranceMs: number;

  private readonly now: () => number;

  /** WebM 초기화 구간. 만료 대상이 아니며 모든 스냅샷 맨 앞에 붙는다. */
  private header: Blob | null = null;

  private chunks: BufferedChunk[] = [];

  constructor({
    windowMs = AUDIO_CLIP_WINDOW_MS,
    gapToleranceMs = SEGMENT_GAP_TOLERANCE_MS,
    now = () => Date.now(),
  }: AudioRingBufferOptions = {}) {
    this.windowMs = windowMs;
    this.gapToleranceMs = gapToleranceMs;
    this.now = now;
  }

  setHeader(header: Blob): void {
    this.header = header;
  }

  /**
   * 오디오 조각을 추가한다. 빈 조각과 시간이 역행하는 조각은 버퍼를 오염시키므로
   * 무시한다. 조각은 도착 순서대로 시간 오름차순이라고 가정한다.
   */
  append(blob: Blob, startMs: number, endMs: number): void {
    if (blob.size === 0 || endMs <= startMs) {
      return;
    }
    this.chunks.push({ blob, startMs, endMs });
    this.evictExpired();
  }

  /**
   * 지금 업로드할 수 있는 실제 캡처 시간 합(ms). MIN_UPLOADABLE 판정의 입력이다.
   * 아직 dataavailable 로 회수되지 않은 미완성 조각(최대 timeslice 길이)은
   * 포함하지 않는다 — 10초 단위 판정에는 영향이 없다.
   */
  availableMs(): number {
    this.evictExpired();
    return this.chunks.reduce((sum, chunk) => sum + (chunk.endMs - chunk.startMs), 0);
  }

  /**
   * 현재 버퍼 내용으로 클립을 만든다. 헤더가 없거나(아직 첫 조각 전) 생존 조각이
   * 없으면 null. 버퍼는 비우지 않는다 — 업로드가 실패해도 오디오를 잃지 않고,
   * 연달아 온 요청도 각자 "최근 300초"를 받는다.
   */
  snapshot(): AudioClipSnapshot | null {
    this.evictExpired();
    if (this.header === null || this.chunks.length === 0) {
      return null;
    }

    const mimeType = this.header.type || this.chunks[0].blob.type;
    const blob = new Blob([this.header, ...this.chunks.map((chunk) => chunk.blob)], {
      type: mimeType,
    });
    const segments = this.mergeSegments();
    return {
      blob,
      mimeType,
      capturedFromMs: this.chunks[0].startMs,
      capturedToMs: this.chunks[this.chunks.length - 1].endMs,
      durationMs: segments.reduce((sum, segment) => sum + (segment.toMs - segment.fromMs), 0),
      segments,
    };
  }

  /** 헤더 포함 전체를 비운다. recorder 재생성(장치 교체 등) 시 호출한다. */
  reset(): void {
    this.header = null;
    this.chunks = [];
  }

  /** 가장 최신 샘플(endMs)까지 창 밖으로 밀려난 조각을 앞에서부터 버린다. */
  private evictExpired(): void {
    const cutoff = this.now() - this.windowMs;
    while (this.chunks.length > 0 && this.chunks[0].endMs <= cutoff) {
      this.chunks.shift();
    }
  }

  /** 연속된 조각을 허용 오차 안에서 이어붙여 캡처 구간 목록으로 만든다. */
  private mergeSegments(): CapturedSegment[] {
    const segments: Array<{ fromMs: number; toMs: number }> = [];
    for (const chunk of this.chunks) {
      const last = segments[segments.length - 1];
      if (last !== undefined && chunk.startMs - last.toMs <= this.gapToleranceMs) {
        last.toMs = Math.max(last.toMs, chunk.endMs);
      } else {
        segments.push({ fromMs: chunk.startMs, toMs: chunk.endMs });
      }
    }
    return segments;
  }
}
