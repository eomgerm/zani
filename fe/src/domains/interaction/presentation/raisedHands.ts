/**
 * 손든 참가자 목록을 스냅샷과 실시간 이벤트로 유지하는 순수 리듀서.
 *
 * **순번은 쓰지 않는다.** 배열이라 순서가 생기긴 하지만 화면은 이 목록을 집합으로만 쓴다 —
 * 갤러리 타일과 명단 모두 `includes()` 로 포함 여부만 묻고, 순회는 LiveKit 참가자 순서로 한다.
 * 그래서 서버도 밀리초 단위 도착 순서를 보장하지 않는다(같은 밀리초면 Redis 가 identity
 * 사전순으로 정렬한다). 순번을 화면에 노출하게 되면 그 보장부터 서버에 만들어야 한다.
 *
 * ## 스냅샷과 실시간 이벤트의 도착 순서
 *
 * 채널은 **구독을 끝낸 뒤** 스냅샷을 REST 로 받는다. 그 왕복 동안 주제 구독은 이미 살아 있어서,
 * 서버가 스냅샷을 만든 **뒤에** 발생한 손들기가 스냅샷 응답보다 먼저 도착할 수 있다. 스냅샷이
 * 목록을 통째로 갈아 끼우므로, 그대로 두면 방금 받은 손들기가 지워진다.
 *
 * 그렇다고 병합으로 바꿀 수는 없다 — 재연결 스냅샷은 끊긴 사이 내려간 손을 **지울 수 있어야** 한다.
 *
 * 그래서 스냅샷을 기다리는 동안 받은 변경을 모아 두었다가, 스냅샷을 적용한 뒤 다시 흘려보낸다.
 * 손들기 변경은 멱등이라(이미 든 손에 raise 는 무의미, 없는 손에 lower 도 무의미) 스냅샷에 이미
 * 반영된 것을 다시 적용해도 결과가 같다. 덕분에 서버가 watermark 를 내려줄 필요가 없다.
 */

/** 한 참가자의 손 상태 변경. 재적용할 수 있도록 방향까지 들고 있는다. */
interface HandChange {
  readonly identity: string;
  readonly raised: boolean;
}

export interface RaisedHandsState {
  /** 지금 손을 든 참가자. 중복이 없다는 것만 보장한다(순서는 위 설명 참고). */
  readonly identities: readonly string[];
  /**
   * 이번 연결의 스냅샷을 기다리는 동안 받은 변경. 스냅샷이 도착하면 그 위에 다시 얹고 비운다.
   *
   * 스냅샷을 끝내 못 받으면(요청 실패) 이 연결이 끊길 때까지 쌓인다. 항목이 참가자당 두 필드짜리
   * 객체라 한 수업 분량이 쌓여도 문제되는 크기가 아니고, 다음 연결에서 비워진다.
   */
  readonly pendingSinceConnect: readonly HandChange[];
  /** 스냅샷을 기다리는 중인지. 기다리는 동안에만 변경을 모은다. */
  readonly awaitingSnapshot: boolean;
}

export type RaisedHandsAction =
  /** 채널이 (다시) 붙었다. 이전 연결에서 모은 변경은 이번 스냅샷과 무관하므로 버린다. */
  | { type: "connected" }
  | { type: "snapshot"; identities: readonly string[] }
  | { type: "raised"; identity: string }
  | { type: "lowered"; identity: string };

export const initialRaisedHandsState: RaisedHandsState = {
  identities: [],
  pendingSinceConnect: [],
  // 첫 스냅샷이 오기 전에 도착하는 이벤트부터 모은다. 마운트 직후가 정확히 그 구간이다.
  awaitingSnapshot: true,
};

/**
 * 변경 하나를 목록에 적용한다. 바뀌지 않으면 **같은 배열을 그대로** 돌려준다 — 호출하는 쪽이
 * 참조 비교로 무변경을 알아채 새 상태 객체를 만들지 않게 한다.
 */
function applyChange(identities: readonly string[], change: HandChange): readonly string[] {
  const present = identities.includes(change.identity);
  if (change.raised) {
    // 이미 있으면 배열을 그대로 둔다. 서버가 재시도에도 알림을 다시 보내고 스냅샷 뒤 재적용도 있어서
    // 같은 이벤트가 여러 번 지나가는데, 그때마다 목록이 바뀌면 참조가 달라져 화면이 불필요하게 다시
    // 그려진다. 순번 때문이 아니라 **재수신에도 목록이 안정적이어야** 하기 때문이다.
    return present ? identities : [...identities, change.identity];
  }
  return present ? identities.filter((id) => id !== change.identity) : identities;
}

export function raisedHandsReducer(
  state: RaisedHandsState,
  action: RaisedHandsAction,
): RaisedHandsState {
  switch (action.type) {
    case "connected":
      return { ...state, pendingSinceConnect: [], awaitingSnapshot: true };

    case "snapshot": {
      // 스냅샷은 만들어진 시점의 사진이다. 그 뒤에 도착한 변경을 다시 얹어야 지금이 된다.
      const identities = state.pendingSinceConnect.reduce(applyChange, [
        ...action.identities,
      ] as readonly string[]);
      return { identities, pendingSinceConnect: [], awaitingSnapshot: false };
    }

    case "raised":
    case "lowered": {
      const change: HandChange = { identity: action.identity, raised: action.type === "raised" };
      const identities = applyChange(state.identities, change);

      if (!state.awaitingSnapshot) {
        return identities === state.identities ? state : { ...state, identities };
      }
      return {
        ...state,
        identities,
        pendingSinceConnect: [...state.pendingSinceConnect, change],
      };
    }
  }
}
