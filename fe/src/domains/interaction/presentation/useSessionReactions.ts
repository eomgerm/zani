"use client";

import { useCallback, useEffect, useRef, useState } from "react";

import { reactionOf } from "../infrastructure/sessionEvent";
import { reactionEmojiOf, type ReactionKind } from "./reactions";
import { useSessionChannel } from "./SessionChannelProvider";

/**
 * 떠오르는 반응. 서버가 뿌린 것만 그린다.
 *
 * **내 반응도 낙관적으로 그리지 않는다.** 그러면 서버가 연타 제한으로 거절한 것까지 내 화면에만
 * 뜨고, echo 가 도착하면 같은 반응이 두 번 떠오른다. 왕복 한 번은 눈에 띄지 않는다.
 *
 * 표시 시간은 서버가 정하지 않는다. 애니메이션이 끝나면 사라지는 값이라 서버가 관리할 상태가 없다.
 */

/** 떠오르는 애니메이션 길이. 강의실의 `zFloat` 지속 시간과 같아야 한다. */
export const REACTION_FLOAT_MS = 2_400;

export interface FloatingReaction {
  /** 서버가 부여한 eventId. 같은 이벤트가 두 번 와도 키가 같아 하나로 남는다. */
  readonly key: string;
  readonly emoji: string;
  /** 화면 가로 위치(%). 겹쳐 보이지 않게 흩뿌린다. */
  readonly left: number;
}

export interface UseSessionReactionsResult {
  readonly reactions: readonly FloatingReaction[];
  readonly canReact: boolean;
  react: (kind: ReactionKind) => void;
}

let fallbackSequence = 0;

function defaultClientEventId(): string {
  if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") {
    return crypto.randomUUID();
  }
  fallbackSequence += 1;
  return `r-${Date.now()}-${fallbackSequence}`;
}

export interface UseSessionReactionsOptions {
  createClientEventId?: () => string;
  floatMs?: number;
  /** 가로 위치를 정하는 난수. 테스트에서 고정하려고 열어 둔다. */
  random?: () => number;
}

export function useSessionReactions(
  options: UseSessionReactionsOptions = {},
): UseSessionReactionsResult {
  const {
    createClientEventId = defaultClientEventId,
    floatMs = REACTION_FLOAT_MS,
    random = Math.random,
  } = options;
  const { state, publishReaction, addEventListener } = useSessionChannel();
  const [reactions, setReactions] = useState<readonly FloatingReaction[]>([]);
  const timers = useRef(new Map<string, ReturnType<typeof setTimeout>>());

  useEffect(() => {
    const pending = timers.current;
    return () => {
      pending.forEach(clearTimeout);
      pending.clear();
    };
  }, []);

  useEffect(
    () =>
      addEventListener((event) => {
        if (event.type !== "REACTION") return;
        const reaction = reactionOf(event);
        if (reaction === null) return;
        const emoji = reactionEmojiOf(reaction);
        if (emoji === null) return;
        // 이미 떠 있는 이벤트면 타이머를 새로 걸지 않는다 — 걸면 먼저 건 타이머가 잊혀 영원히 남는다.
        if (timers.current.has(event.eventId)) return;

        setReactions((current) => [
          ...current,
          { key: event.eventId, emoji, left: 20 + random() * 60 },
        ]);
        timers.current.set(
          event.eventId,
          setTimeout(() => {
            timers.current.delete(event.eventId);
            setReactions((current) => current.filter((item) => item.key !== event.eventId));
          }, floatMs),
        );
      }),
    [addEventListener, floatMs, random],
  );

  const canReact = state === "connected";

  const react = useCallback(
    (kind: ReactionKind) => {
      if (!canReact) return;
      publishReaction(createClientEventId(), kind);
    },
    [canReact, createClientEventId, publishReaction],
  );

  return { reactions, canReact, react };
}
