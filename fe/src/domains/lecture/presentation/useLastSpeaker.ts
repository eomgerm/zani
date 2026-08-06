"use client";

import { useState } from "react";

import type { ParticipantTileData } from "./components/room/ParticipantTile";

/**
 * 마지막으로 발화가 잡힌 참가자를 붙들어 둔다.
 *
 * <p>발표자 보기가 누구를 띄울지 정할 때 쓴다. 지금 이 순간 소리를 내는 사람만 보면 말 사이의 짧은
 * 침묵마다 강사로 돌아갔다 다시 넘어와 화면이 널뛴다(!126 리뷰에서 한 번 잡힌 문제다).
 *
 * <p><b>보기를 전환하는 컴포넌트보다 위에서 불러야 한다.</b> 발표자 보기 컴포넌트 안에 두면 갤러리로
 * 갔다 오는 순간 언마운트되면서 기억이 사라져, 돌아올 때마다 강사부터 다시 시작한다.
 *
 * @returns 직전 발화자 identity. 아직 아무도 말하지 않았으면 `null`.
 */
export function useLastSpeaker(participants: readonly ParticipantTileData[]): string | null {
  const [lastSpeakerId, setLastSpeakerId] = useState<string | null>(null);

  // 무대에 선 사람이 아직 말하는 중이면 유지한다. find 는 배열 순서(로컬 우선)라, 이 가드가 없으면
  // 동시 발화 때 순서상 앞선 참가자가 말하던 사람의 자리를 뺏는다.
  const stillSpeaking = participants.some(
    (participant) => participant.id === lastSpeakerId && participant.speaking,
  );
  const speakingNow = participants.find((participant) => participant.speaking);
  if (!stillSpeaking && speakingNow !== undefined && speakingNow.id !== lastSpeakerId) {
    setLastSpeakerId(speakingNow.id);
  }

  return lastSpeakerId;
}
