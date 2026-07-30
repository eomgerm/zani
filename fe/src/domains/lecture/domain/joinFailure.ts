/**
 * 입장이 거절된 이유. HTTP 상태·업무 코드 같은 전송 계층 용어가 아니라 사용자가 처한 상황으로만 표현한다.
 *
 * <p>서버 응답을 이 값으로 옮기는 일은 infrastructure 가 한다({@code joinFailureReasonOf}). 도메인이 전송 계층의 오류 타입을 알면 계층이 역전된다.
 */
export type JoinFailureReason =
  /** 강사가 아직 수업을 시작하지 않았다. 시작하면 같은 코드로 들어올 수 있다. */
  | "NOT_STARTED"
  /** 이미 끝난 수업이다. 다시 들어올 수 없다. */
  | "ENDED"
  /** 정원(강사 포함 30명)이 찼다. */
  | "FULL"
  /** 그런 초대 코드의 수업이 없다. */
  | "NOT_FOUND"
  /** 코드 형식이 어긋났다. */
  | "INVALID_CODE"
  /** 로그인이 풀렸다. */
  | "SIGNED_OUT"
  /** 위 어느 것으로도 좁히지 못했다. */
  | "UNKNOWN";

/**
 * 거절 이유별 사용자 안내 문구.
 *
 * <p>문구를 화면 밖에 두는 이유가 있다. 이유를 판단하는 곳이 홈과 입장 전 점검 두 군데인데, 화면마다 복사해 두면 한쪽만 고쳐져 서로 다른 이유를 같은 문구로 알리게 된다. 실제로 그렇게 어긋난 적이
 * 있다 — 정원 초과와 시작 전 문구가 서로 바뀌어 있었다.
 *
 * @param sentCode 서버로 보낸 정규화된 초대 코드. 형식 오류일 때만 문구에 넣는다 — 링크가 잘린 건지 코드가 바뀐 건지 사용자가 구분할 수 있어야 한다.
 */
export const joinFailureMessage = (reason: JoinFailureReason, sentCode: string): string => {
  switch (reason) {
    case "NOT_STARTED":
      return "강사가 아직 수업을 시작하지 않았어요. 시작한 뒤 다시 시도해 주세요.";
    case "ENDED":
      return "이미 끝난 수업이에요. 강사에게 확인해 주세요.";
    case "FULL":
      return "정원이 가득 찼어요. 강사에게 문의해 주세요.";
    case "NOT_FOUND":
      return "그런 초대 코드의 수업이 없어요. 코드를 다시 확인해 주세요.";
    case "INVALID_CODE":
      return `초대 코드 형식이 올바르지 않아요. 영문·숫자 8자여야 합니다. (보낸 코드: ${sentCode})`;
    case "SIGNED_OUT":
      return "로그인이 필요해요. 다시 로그인한 뒤 시도해 주세요.";
    case "UNKNOWN":
      return "입장하지 못했어요. 잠시 후 다시 시도해 주세요.";
  }
};
