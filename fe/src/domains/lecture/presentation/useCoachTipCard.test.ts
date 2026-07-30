import { act, renderHook } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { useCoachTipCard } from "./useCoachTipCard";
import type { CoachPollResult, CoachTip } from "../infrastructure/coachPollApi";

const tip = (title: string): CoachTip => ({
  tipType: "CONFUSED",
  title,
  message: "전체 학생의 30%가 현재 내용을 헷갈려 하고 있어요.",
  targetConcept: "클로저",
});

const poll = (triggerId: string | null, value: CoachTip | null): CoachPollResult => ({
  triggerId,
  tip: value,
  unavailableReason: null,
});

describe("useCoachTipCard", () => {
  it("shows nothing before a tip arrives", () => {
    const { result } = renderHook(() => useCoachTipCard());

    expect(result.current.tip).toBeNull();
  });

  it("shows the tip a poll delivered", () => {
    const { result } = renderHook(() => useCoachTipCard());

    act(() => result.current.accept(poll("t-1", tip("추가 설명이 필요해요"))));

    expect(result.current.tip?.title).toBe("추가 설명이 필요해요");
  });

  // 폴링은 10초마다 도는데 서버는 쿨타임 동안 같은 트리거를 계속 내려줄 수 있다.
  it("does not show the same trigger twice", () => {
    const { result } = renderHook(() => useCoachTipCard());
    const same = poll("t-1", tip("추가 설명이 필요해요"));

    act(() => result.current.accept(same));
    act(() => result.current.dismiss());
    act(() => result.current.accept(same));

    expect(result.current.tip).toBeNull();
  });

  it("replaces the current tip when a new trigger arrives", () => {
    const { result } = renderHook(() => useCoachTipCard());

    act(() => result.current.accept(poll("t-1", tip("추가 설명이 필요해요"))));
    act(() => result.current.accept(poll("t-2", tip("내용을 다시 짚어주세요"))));

    expect(result.current.tip?.title).toBe("내용을 다시 짚어주세요");
  });

  it("closes on 확인·닫기", () => {
    const { result } = renderHook(() => useCoachTipCard());

    act(() => result.current.accept(poll("t-1", tip("추가 설명이 필요해요"))));
    act(() => result.current.dismiss());

    expect(result.current.tip).toBeNull();
  });

  // 강사가 읽는 중에 다음 폴링이 카드를 지우면 안 된다.
  it("leaves an open card alone while polls come back empty", () => {
    const { result } = renderHook(() => useCoachTipCard());

    act(() => result.current.accept(poll("t-1", tip("추가 설명이 필요해요"))));
    act(() => result.current.accept(poll(null, null)));

    expect(result.current.tip?.title).toBe("추가 설명이 필요해요");
  });

  // 미표시 사유로 팁이 없는 응답은 카드를 띄우지 않는다(86 요구사항).
  it("shows nothing when the server could not make a tip", () => {
    const { result } = renderHook(() => useCoachTipCard());

    act(() =>
      result.current.accept({
        triggerId: "t-1",
        tip: null,
        unavailableReason: "TRANSCRIPTION_FAILED",
      }),
    );

    expect(result.current.tip).toBeNull();
  });

  it("ignores a tip that arrives without a trigger id", () => {
    const { result } = renderHook(() => useCoachTipCard());

    act(() => result.current.accept(poll(null, tip("추가 설명이 필요해요"))));

    expect(result.current.tip).toBeNull();
  });
});
