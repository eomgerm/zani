export type LogoutRequester = (signal?: AbortSignal) => Promise<void>;

export class LogoutRequestError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "LogoutRequestError";
  }
}

/**
 * refresh 쿠키로 식별한 세션을 서버에서 지운다. 실패해도(네트워크 오류 등) 호출하는 쪽
 * (AuthProvider.logout)이 로컬 상태는 이미 지운 뒤이므로, 여기서는 조용히 실패를 알리기만 한다.
 */
export const logout: LogoutRequester = async (signal) => {
  const apiBaseUrl = (process.env.NEXT_PUBLIC_API_BASE_URL ?? "").replace(/\/$/, "");
  const response = await fetch(`${apiBaseUrl}/api/v1/auth/logout`, {
    method: "POST",
    headers: { Accept: "application/json" },
    credentials: "include",
    signal,
  });

  if (!response.ok) {
    throw new LogoutRequestError(`Logout request failed with status ${response.status}.`);
  }
};
