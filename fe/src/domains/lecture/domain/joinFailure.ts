import { JoinSessionRequestError } from "../infrastructure/joinSessionApi";

/**
 * 서버가 입장을 거절한 이유별 안내 문구.
 *
 * <p>매핑을 화면 밖에 두는 이유가 있다. 코드를 판단하는 곳이 홈과 입장 전 점검 두 군데인데, 화면마다 복사해 두면 한쪽만 고쳐져 서로 다른 이유를 같은 문구로 알리게 된다. 실제로 그렇게 어긋난
 * 적이 있다 — 정원 초과와 시작 전 문구가 서로 바뀌어 있었다.
 *
 * <p>코드 값의 출처는 백엔드 {@code SessionApplicationErrorCode} 다. 여기를 고칠 때는 그쪽과 대조해야 한다.
 */

/** 아직 시작하지 않은 수업. 강사가 시작하면 같은 코드로 들어올 수 있다. */
const SESSION_NOT_STARTED = "SESSION_APP_007";
/** 이미 끝난 수업. 다시 들어올 수 없다. */
const SESSION_ENDED = "SESSION_APP_008";
/** 정원(강사 포함 30명)이 찼다. */
const SESSION_FULL = "SESSION_APP_009";

/** 초대 코드의 수업이 없을 때. */
const NOT_FOUND_MESSAGE = "그런 초대 코드의 수업이 없어요. 코드를 다시 확인해 주세요.";
const SIGNED_OUT_MESSAGE = "로그인이 필요해요. 다시 로그인한 뒤 시도해 주세요.";
const FALLBACK_MESSAGE = "입장하지 못했어요. 잠시 후 다시 시도해 주세요.";

/**
 * 입장 실패를 사용자 문구로 옮긴다. 서버가 돌려준 업무 코드를 HTTP 상태보다 먼저 본다.
 *
 * @param sentCode 서버로 보낸 정규화된 초대 코드. 형식 오류(400)일 때만 문구에 넣는다 — 링크가 잘린 건지 코드가 바뀐 건지 사용자가 구분할 수 있어야 한다.
 */
export const joinFailureMessage = (error: unknown, sentCode: string): string => {
  if (!(error instanceof JoinSessionRequestError)) {
    return FALLBACK_MESSAGE;
  }

  switch (error.code) {
    case SESSION_NOT_STARTED:
      return "강사가 아직 수업을 시작하지 않았어요. 시작한 뒤 다시 시도해 주세요.";
    case SESSION_ENDED:
      return "이미 끝난 수업이에요. 강사에게 확인해 주세요.";
    case SESSION_FULL:
      return "정원이 가득 찼어요. 강사에게 문의해 주세요.";
    default:
      break;
  }

  if (error.status === 404) {
    return NOT_FOUND_MESSAGE;
  }
  if (error.status === 400) {
    return `초대 코드 형식이 올바르지 않아요. 영문·숫자 8자여야 합니다. (보낸 코드: ${sentCode})`;
  }
  if (error.status === 401) {
    return SIGNED_OUT_MESSAGE;
  }
  return FALLBACK_MESSAGE;
};
