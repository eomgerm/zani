/**
 * 손든 참가자 목록을 스냅샷과 실시간 이벤트로 유지하는 순수 리듀서.
 *
 * 순번이 곧 배열 인덱스다. 서버가 손든 순서대로 스냅샷을 주고 이후 이벤트는 뒤에 붙으므로,
 * 이 규칙만 지키면 클라이언트가 손든 시각을 따로 들고 있지 않아도 순번이 맞는다.
 */

export interface RaisedHandsState {
  /** 손든 순서. 중복 없이 유지한다. */
  readonly identities: readonly string[];
}

export type RaisedHandsAction =
  | { type: "snapshot"; identities: readonly string[] }
  | { type: "raised"; identity: string }
  | { type: "lowered"; identity: string };

export const initialRaisedHandsState: RaisedHandsState = { identities: [] };

export function raisedHandsReducer(
  state: RaisedHandsState,
  action: RaisedHandsAction,
): RaisedHandsState {
  switch (action.type) {
    case "snapshot":
      // 재연결 스냅샷이 기준이다. 끊겨 있는 동안의 변화는 어차피 못 받았으므로 병합하지 않고 갈아 끼운다.
      return { identities: [...action.identities] };

    case "raised":
      // 이미 있으면 그대로 둔다. 서버가 재시도에도 알림을 다시 보내므로 같은 이벤트가 두 번 올 수 있고,
      // 그때 뒤로 옮기면 먼저 든 사람이 밀린다.
      return state.identities.includes(action.identity)
        ? state
        : { identities: [...state.identities, action.identity] };

    case "lowered": {
      if (!state.identities.includes(action.identity)) return state;
      return { identities: state.identities.filter((id) => id !== action.identity) };
    }
  }
}
