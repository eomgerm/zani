/**
 * 프레임 루프 어댑터. 판정 오케스트레이션이 `requestAnimationFrame` 에 직접 묶이지 않도록
 * 분리했다. 테스트에서는 수동 스케줄러로 대체한다.
 */
export interface FrameScheduler {
  request(callback: (timestampMs: number) => void): number;
  cancel(handle: number): void;
}

export const ANIMATION_FRAME_SCHEDULER: FrameScheduler = {
  request: (callback) => requestAnimationFrame(callback),
  cancel: (handle) => cancelAnimationFrame(handle),
};
