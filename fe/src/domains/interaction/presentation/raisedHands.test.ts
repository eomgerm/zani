import { describe, expect, it } from "vitest";

import {
  initialRaisedHandsState,
  raisedHandsReducer,
  type RaisedHandsAction,
  type RaisedHandsState,
} from "./raisedHands";

/** 스냅샷을 이미 받아 정착한 상태. 평소 동작을 볼 때 쓴다. */
const settled = (identities: readonly string[]): RaisedHandsState => ({
  identities,
  pendingSinceConnect: [],
  awaitingSnapshot: false,
});

const reduce = (state: RaisedHandsState, ...actions: RaisedHandsAction[]) =>
  actions.reduce(raisedHandsReducer, state).identities;

describe("raisedHandsReducer", () => {
  it("손든 순서대로 뒤에 붙인다", () => {
    expect(
      reduce(settled([]), { type: "raised", identity: "p-22" }, { type: "raised", identity: "p-11" }),
    ).toEqual(["p-22", "p-11"]);
  });

  /** 서버 재시도와 스냅샷 뒤 재적용으로 같은 이벤트가 여러 번 지나간다. 그때마다 목록이 바뀌면 화면이 불필요하게 다시 그려진다. */
  it("이미 든 손이 다시 와도 목록이 흔들리지 않는다", () => {
    expect(reduce(settled(["p-22", "p-11"]), { type: "raised", identity: "p-22" })).toEqual([
      "p-22",
      "p-11",
    ]);
  });

  it("같은 손이 다시 와도 새 객체를 만들지 않는다", () => {
    const state = settled(["p-22"]);

    expect(raisedHandsReducer(state, { type: "raised", identity: "p-22" })).toBe(state);
    expect(raisedHandsReducer(state, { type: "lowered", identity: "p-11" })).toBe(state);
  });

  it("손을 내리면 목록에서 빠지고 나머지 순서는 유지된다", () => {
    expect(reduce(settled(["p-22", "p-11", "p-33"]), { type: "lowered", identity: "p-11" })).toEqual(
      ["p-22", "p-33"],
    );
  });

  /** 끊겨 있는 동안의 변화는 못 받았으므로 병합하면 이미 내린 손이 남는다. */
  it("스냅샷은 병합하지 않고 갈아 끼운다", () => {
    expect(reduce(settled(["p-99"]), { type: "snapshot", identities: ["p-22", "p-11"] })).toEqual([
      "p-22",
      "p-11",
    ]);
  });

  it("첫 스냅샷을 받기 전부터 변경을 모은다", () => {
    expect(initialRaisedHandsState.identities).toEqual([]);
    expect(initialRaisedHandsState.awaitingSnapshot).toBe(true);
  });

  /**
   * 채널은 구독을 끝낸 뒤 스냅샷을 REST 로 받는다. 그 왕복 동안 주제 구독은 이미 살아 있어서, 서버가
   * 스냅샷을 만든 뒤에 발생한 손들기가 스냅샷 응답보다 먼저 도착할 수 있다.
   */
  describe("스냅샷과 실시간 이벤트의 도착 순서가 역전될 때", () => {
    it("스냅샷보다 먼저 도착한 손들기가 지워지지 않는다", () => {
      expect(
        reduce(
          initialRaisedHandsState,
          { type: "connected" },
          { type: "raised", identity: "p-22" }, // 스냅샷 응답 전에 도착
          { type: "snapshot", identities: [] }, // 이 손들기 이전에 만들어진 스냅샷
        ),
      ).toEqual(["p-22"]);
    });

    it("스냅샷보다 먼저 도착한 손내리기도 그대로 반영된다", () => {
      expect(
        reduce(
          initialRaisedHandsState,
          { type: "connected" },
          { type: "lowered", identity: "p-11" },
          { type: "snapshot", identities: ["p-11", "p-22"] },
        ),
      ).toEqual(["p-22"]);
    });

    /** 재적용이 멱등이라 워터마크 없이 맞는다는 것이 이 설계의 전제다. */
    it("스냅샷에 이미 반영된 이벤트를 다시 받아도 중복되지 않는다", () => {
      expect(
        reduce(
          initialRaisedHandsState,
          { type: "connected" },
          { type: "raised", identity: "p-11" },
          { type: "snapshot", identities: ["p-11"] },
        ),
      ).toEqual(["p-11"]);
    });

    it("모아 둔 변경은 스냅샷을 적용하면 비워진다", () => {
      const afterSnapshot = [
        { type: "connected" } as const,
        { type: "raised", identity: "p-22" } as const,
        { type: "snapshot", identities: [] } as const,
      ].reduce(raisedHandsReducer, initialRaisedHandsState);

      expect(afterSnapshot.pendingSinceConnect).toEqual([]);
      expect(afterSnapshot.awaitingSnapshot).toBe(false);
    });
  });

  /**
   * 재연결 스냅샷이 여전히 기준이어야 한다. 위 복구 로직이 이 성질을 깨면, 끊긴 사이 내려간 손이
   * 화면에 영원히 남는다.
   */
  describe("재연결", () => {
    it("끊긴 사이 내려간 손은 재연결 스냅샷이 지운다", () => {
      const firstConnection = [
        { type: "connected" } as const,
        { type: "raised", identity: "p-11" } as const,
        { type: "snapshot", identities: ["p-11"] } as const,
      ].reduce(raisedHandsReducer, initialRaisedHandsState);

      expect(
        reduce(firstConnection, { type: "connected" }, { type: "snapshot", identities: [] }),
      ).toEqual([]);
    });

    /** 이전 연결에서 모은 변경을 다시 얹으면, 끊긴 사이 내려간 손이 되살아난다. */
    it("이전 연결에서 모은 변경은 새 연결 스냅샷에 얹지 않는다", () => {
      const droppedMidFlight = [
        { type: "connected" } as const,
        { type: "raised", identity: "p-11" } as const, // 스냅샷을 못 받은 채 연결이 끊겼다
      ].reduce(raisedHandsReducer, initialRaisedHandsState);

      expect(
        reduce(droppedMidFlight, { type: "connected" }, { type: "snapshot", identities: [] }),
      ).toEqual([]);
    });
  });
});
