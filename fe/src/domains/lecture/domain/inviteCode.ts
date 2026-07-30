/** 서버가 저장·조회에 쓰는 초대 코드 형태: 대문자 영숫자 8자. */
const CANONICAL = /^[A-Z0-9]{8}$/;

/**
 * 초대 코드를 서버가 저장하는 형태로 맞춘다: 하이픈·공백을 없애고 대문자로 올린다.
 *
 * <p>표시형(`GPH7-GQ5Q`)이나 소문자를 그대로 보내면 서버 구성에 따라 8자 검증에 걸려 400 이 된다. 사용자가 코드를 어떤 형태로 옮겨 적었는지에 결과가 달라지지 않도록 보내기
 * 전에 여기서 맞춘다.
 */
export const canonicalInviteCode = (raw: string) => raw.replace(/[\s-]/g, "").toUpperCase();

/**
 * 사용자가 입력한 값에서 초대 코드를 뽑는다. 코드만(`gph7gq5q`, `GPH7-GQ5Q`) 넣어도 되고, 받은 초대 링크를 통째로 붙여 넣어도 된다.
 *
 * <p>강사가 공유하는 건 링크(`http://localhost:3000/prejoin/GPH7GQ5Q`)다. 그래서 링크에서 코드만 골라내는 일을 사용자에게 맡기면 안 된다 — 링크를 그대로 보내면
 * 서버가 형식 오류로 거절한다.
 *
 * <p>형식이 어긋나면 null 을 돌려준다. 그러면 서버까지 보내 400 을 받는 대신 입력한 화면에서 바로 알려줄 수 있다.
 */
export const inviteCodeFrom = (input: string): string | null => {
  // 쿼리·프래그먼트를 떼고, 링크면 마지막 경로 조각이 코드다. 코드만 넣었을 때도 조각이 하나뿐이라 같은 규칙으로 처리된다.
  const path = input.trim().split(/[?#]/)[0];
  const lastSegment = path.split("/").filter((segment) => segment.length > 0).at(-1) ?? "";
  const code = canonicalInviteCode(lastSegment);

  return CANONICAL.test(code) ? code : null;
};
