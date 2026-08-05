"use client";

import {
  requestGroupAttentionTimeline,
  type GroupAttentionTimeline,
  type GroupTimelineRequester,
} from "../infrastructure/attentionTimelineApi";
import { useAttentionTimeline, type UseAttentionTimelineResult } from "./useAttentionTimeline";

/**
 * 집단 참여도 타임라인을 한 번 조회한다.
 *
 * <p>`GroupAttentionTimeline` 카드가 스스로 부르는 것과 같은 조회다. 이 훅을 따로 내보내는 이유는
 * 강사 리포트 화면이 같은 응답을 두 자리에 쓰기 때문이다 — 카드는 흐름을 그리고, 한눈에 보기는 그
 * 점들을 세어 집중 구간 비율을 낸다. 화면이 한 번 부른 뒤 카드에 `source` 로 넘겨 준다.
 */
export function useGroupAttentionTimeline(
  sessionId: string,
  request: GroupTimelineRequester = requestGroupAttentionTimeline,
): UseAttentionTimelineResult<GroupAttentionTimeline> {
  return useAttentionTimeline<GroupAttentionTimeline>({ sessionId, enabled: true, request });
}
