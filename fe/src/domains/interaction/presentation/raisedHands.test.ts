import { describe, expect, it } from "vitest";

import { initialRaisedHandsState, raisedHandsReducer } from "./raisedHands";

const reduce = (
  identities: readonly string[],
  ...actions: Parameters<typeof raisedHandsReducer>[1][]
) => actions.reduce(raisedHandsReducer, { identities }).identities;

describe("raisedHandsReducer", () => {
  it("손든 순서대로 뒤에 붙인다", () => {
    expect(
      reduce([], { type: "raised", identity: "p-22" }, { type: "raised", identity: "p-11" }),
    ).toEqual(["p-22", "p-11"]);
  });

  /** 서버가 재시도에도 알림을 다시 보내므로 같은 이벤트가 두 번 온다. 그때 뒤로 옮기면 먼저 든 사람이 밀린다. */
  it("이미 든 손이 다시 와도 순번이 밀리지 않는다", () => {
    expect(reduce(["p-22", "p-11"], { type: "raised", identity: "p-22" })).toEqual([
      "p-22",
      "p-11",
    ]);
  });

  it("같은 손이 다시 와도 새 객체를 만들지 않는다", () => {
    const state = { identities: ["p-22"] };

    expect(raisedHandsReducer(state, { type: "raised", identity: "p-22" })).toBe(state);
    expect(raisedHandsReducer(state, { type: "lowered", identity: "p-11" })).toBe(state);
  });

  it("손을 내리면 목록에서 빠지고 나머지 순서는 유지된다", () => {
    expect(reduce(["p-22", "p-11", "p-33"], { type: "lowered", identity: "p-11" })).toEqual([
      "p-22",
      "p-33",
    ]);
  });

  /** 끊겨 있는 동안의 변화는 못 받았으므로 병합하면 이미 내린 손이 남는다. */
  it("스냅샷은 병합하지 않고 갈아 끼운다", () => {
    expect(reduce(["p-99"], { type: "snapshot", identities: ["p-22", "p-11"] })).toEqual([
      "p-22",
      "p-11",
    ]);
  });

  it("초기 상태는 비어 있다", () => {
    expect(initialRaisedHandsState.identities).toEqual([]);
  });
});
