/**
 * 재생 위치 → 전사 활성 행 계산. 렌더와 분리된 순수 함수라 단독으로 테스트한다
 * (timelineTrack 과 같은 배치).
 */

/** 커서 계산에 필요한 최소 모양. 어댑터의 TranscriptSegment 가 그대로 들어온다. */
export type CursorSegment = { readonly startSeconds: number };

/**
 * 지금 재생 위치가 가리키는 전사 행의 인덱스. **시작 시각 오름차순 정렬을 전제한다**
 * (어댑터가 보장).
 *
 * <p>구간 [start, end) 포함 검사가 아니라 "시작 시각이 지난 마지막 행"을 고른다. 전사 행
 * 사이에는 발화가 없는 틈이 있는데, 틈에서 하이라이트를 꺼 버리면 재생 내내 강조가 깜빡인다.
 * 다음 발화가 시작되기 전까지는 직전 발화가 문맥이다.
 *
 * <p>첫 행보다 앞이면 -1 — 아직 아무 발화도 지나지 않았다.
 *
 * <p>timeupdate(초당 약 4회)마다 불리므로 이진 탐색으로 짚는다. 세 시간 수업이면 행이
 * 수천 개다.
 */
export function activeSegmentIndex(
  segments: readonly CursorSegment[],
  currentSeconds: number,
): number {
  let low = 0;
  let high = segments.length - 1;
  let found = -1;

  while (low <= high) {
    const mid = (low + high) >> 1;
    if (segments[mid].startSeconds <= currentSeconds) {
      found = mid;
      low = mid + 1;
    } else {
      high = mid - 1;
    }
  }

  return found;
}
