import type {
  StudentTimelinePoint,
  StudentTimelineState,
} from "../infrastructure/attentionTimelineApi";

/**
 * 상태 막대가 그리는 구간 하나.
 *
 * <p>강사 카드는 학생 상태 대신 흐트러짐 구간을 담으므로 `state` 가 늘 `null` 이다.
 */
export type TrackSegment = {
  readonly startSeconds: number;
  readonly endSeconds: number;
  readonly state: StudentTimelineState | null;
};

/**
 * 인접한 같은 상태를 하나로 묶는다. 값을 다시 계산하지 않고 이웃한 같은 값을 잇기만 한다.
 *
 * <p>3시간이면 2,160 포인트라 점 하나에 `<button>` 하나를 두면 DOM 이 감당하지 못한다. 병합은
 * 그 수를 줄이기 위한 것이며 비율·상태를 재계산하는 것이 아니다.
 *
 * <p>`null` 은 옆 상태로 메우지 않는다. 관측이 없는 구간을 이웃으로 채우면 없는 정보를 지어내는
 * 것이다(REPORT-S-007).
 */
export function mergeSegments(
  points: readonly StudentTimelinePoint[],
  intervalSeconds: number,
): TrackSegment[] {
  const segments: TrackSegment[] = [];

  for (const point of points) {
    const previous = segments[segments.length - 1];
    // 앞 구간이 바로 이 점 앞에서 끝나고 상태도 정확히 같을 때만 잇는다(`null === null` 포함).
    if (previous !== undefined && previous.state === point.state && previous.endSeconds === point.offsetSeconds) {
      segments[segments.length - 1] = {
        startSeconds: previous.startSeconds,
        endSeconds: point.offsetSeconds + intervalSeconds,
        state: previous.state,
      };
      continue;
    }

    segments.push({
      startSeconds: point.offsetSeconds,
      endSeconds: point.offsetSeconds + intervalSeconds,
      state: point.state,
    });
  }

  return segments;
}

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
