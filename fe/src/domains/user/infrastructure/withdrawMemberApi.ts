export class WithdrawMemberRequestError extends Error {
  /** HTTP 상태. 응답을 받지 못했으면 0. */
  readonly status: number;

  constructor(message: string, status: number) {
    super(message);
    this.name = "WithdrawMemberRequestError";
    this.status = status;
  }
}

export type MemberWithdrawer = (accessToken: string, signal?: AbortSignal) => Promise<void>;

/**
 * 회원 탈퇴(DELETE /api/v1/members/me). 서버는 Access Token 의 사용자 본인만 탈퇴시키므로 대상 회원을 따로 넘기지 않는다.
 * 되돌릴 수 없으므로 호출 전에 확인을 받아야 한다.
 *
 * 세션은 이 호출이 끊지 않는다 — 성공한 뒤 이어서 로그아웃까지 불러야 refresh 토큰과 쿠키가 정리된다.
 */
export const withdrawMember: MemberWithdrawer = async (accessToken, signal) => {
  const apiBaseUrl = (process.env.NEXT_PUBLIC_API_BASE_URL ?? "").replace(/\/$/, "");
  const response = await fetch(`${apiBaseUrl}/api/v1/members/me`, {
    method: "DELETE",
    headers: {
      Accept: "application/json",
      Authorization: `Bearer ${accessToken}`,
    },
    credentials: "include",
    signal,
  });

  if (!response.ok) {
    throw new WithdrawMemberRequestError(
      `Withdraw member failed with status ${response.status}.`,
      response.status,
    );
  }
};
