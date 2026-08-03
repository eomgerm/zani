import type { StudentTimelineState } from "../infrastructure/attentionTimelineApi";

/**
 * 상태 막대가 그리는 구간 하나.
 *
 * <p>서버가 인접 동일 상태를 병합해 내려준다(설계 문서 §2.13). 화면은 받은 구간을 그대로 그린다.
 *
 * <p>강사 카드는 학생 상태 대신 흐트러짐 구간을 담으므로 `state` 가 늘 `null` 이다.
 */
export type TrackSegment = {
  readonly startSeconds: number;
  readonly endSeconds: number;
  readonly state: StudentTimelineState | null;
};

/**
 * ←·→·Home·End 로 옮겨 갈 인덱스. 범위를 벗어나지 않는다.
 *
 * <p>끝에서 반대편으로 감기지 않는다 — 시간축이라 마지막 구간의 오른쪽은 처음이 아니다.
 */
export function nextSegmentIndex(current: number, key: string, total: number): number {
  if (total <= 0) {
    return 0;
  }

  const last = total - 1;
  switch (key) {
    case "ArrowRight":
      return Math.min(current + 1, last);
    case "ArrowLeft":
      return Math.max(current - 1, 0);
    case "Home":
      return 0;
    case "End":
      return last;
    default:
      return current;
  }
}
