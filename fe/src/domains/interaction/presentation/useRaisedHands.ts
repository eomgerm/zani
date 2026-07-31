"use client";

import { useCallback, useEffect, useMemo, useReducer } from "react";

import {
  initialRaisedHandsState,
  raisedHandsReducer,
  type RaisedHandsState,
} from "./raisedHands";
import { useSessionChannel } from "./SessionChannelProvider";

/**
 * 손들기. 현재 상태는 서버가 들고 있고 이 훅은 그것을 비춘다.
 *
 * **채팅과 달리 낙관적으로 그리지 않는다.** 채팅은 못 보낸 메시지가 사라지면 안 되니 먼저 그리고
 * 확정을 기다리지만, 손들기는 상태라 틀리게 그렸다가 되돌리는 편이 더 어색하다. 왕복 한 번이면
 * 확정이 오고, 그동안 버튼이 눌린 채로 보이지 않는 쪽이 정직하다.
 */

export interface UseRaisedHandsOptions {
  /** 내 LiveKit participant identity. 연결 전이면 null — 그때는 내 손 상태를 알 수 없다. */
  readonly myIdentity: string | null;
  createClientEventId?: () => string;
}

export interface UseRaisedHandsResult {
  /** 지금 손을 든 참가자. 화면은 포함 여부만 보고 순서는 쓰지 않는다. */
  readonly raisedIdentities: readonly string[];
  readonly myHandRaised: boolean;
  /** 채널이 붙어 있어 바꿀 수 있는지. */
  readonly canToggle: boolean;
  toggle: () => void;
}

let fallbackSequence = 0;

/** `crypto.randomUUID` 가 없는 환경을 위한 대비. `useSessionChat` 과 같은 이유다. */
function defaultClientEventId(): string {
  if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") {
    return crypto.randomUUID();
  }
  fallbackSequence += 1;
  return `h-${Date.now()}-${fallbackSequence}`;
}

export function useRaisedHands(options: UseRaisedHandsOptions): UseRaisedHandsResult {
  const { myIdentity, createClientEventId = defaultClientEventId } = options;
  const { state, snapshot, publishHand, addEventListener } = useSessionChannel();
  const [handsState, dispatch] = useReducer(raisedHandsReducer, initialRaisedHandsState);

  // 스냅샷 효과보다 **먼저** 둔다. 이미 붙어 있는 채널에 늦게 마운트되면 두 효과가 같은 커밋에서
  // 도는데, 순서가 반대면 방금 적용한 스냅샷을 곧바로 "기다리는 중"으로 되돌려 버린다.
  useEffect(() => {
    if (state !== "connected") return;
    dispatch({ type: "connected" });
  }, [state]);

  useEffect(() => {
    if (snapshot === null) return;
    dispatch({ type: "snapshot", identities: snapshot.raisedHandIdentities });
  }, [snapshot]);

  useEffect(
    () =>
      addEventListener((event) => {
        if (event.type === "HAND_RAISED") {
          dispatch({ type: "raised", identity: event.sender.identity });
        } else if (event.type === "HAND_LOWERED") {
          dispatch({ type: "lowered", identity: event.sender.identity });
        }
      }),
    [addEventListener],
  );

  const myHandRaised = useMemo(
    () => myIdentity !== null && handsState.identities.includes(myIdentity),
    [handsState, myIdentity],
  );

  // identity 를 모르면 내 상태를 판정할 수 없어, 눌러도 무엇을 보낼지 정할 수 없다.
  const canToggle = state === "connected" && myIdentity !== null;

  const toggle = useCallback(() => {
    if (!canToggle) return;
    publishHand(createClientEventId(), !myHandRaised);
  }, [canToggle, createClientEventId, myHandRaised, publishHand]);

  return { raisedIdentities: handsState.identities, myHandRaised, canToggle, toggle };
}

export type { RaisedHandsState };
