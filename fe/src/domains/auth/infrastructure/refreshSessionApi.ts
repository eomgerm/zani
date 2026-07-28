export type RefreshSessionResult = {
  accessToken: string;
  accessTokenExpiresAt: string;
};

export type RefreshSessionRequester = (signal?: AbortSignal) => Promise<RefreshSessionResult>;

export class RefreshSessionRequestError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "RefreshSessionRequestError";
  }
}

const isRefreshSessionResult = (value: unknown): value is RefreshSessionResult => {
  if (typeof value !== "object" || value === null) {
    return false;
  }

  const result = value as Record<string, unknown>;
  const isNonBlankString = (field: unknown) =>
    typeof field === "string" && field.trim().length > 0;
  return isNonBlankString(result.accessToken) && isNonBlankString(result.accessTokenExpiresAt);
};

/**
 * HttpOnly refresh 쿠키만으로 새 Access Token 을 받는다. 로그인한 적 없거나 세션이
 * 만료됐으면 401 로 실패하는 게 정상이라, 호출하는 쪽(AuthProvider 부트 복원)에서 조용히 처리한다.
 */
export const refreshSession: RefreshSessionRequester = async (signal) => {
  const apiBaseUrl = (process.env.NEXT_PUBLIC_API_BASE_URL ?? "").replace(/\/$/, "");
  const response = await fetch(`${apiBaseUrl}/api/v1/auth/refresh`, {
    method: "POST",
    headers: { Accept: "application/json" },
    credentials: "include",
    signal,
  });

  if (!response.ok) {
    throw new RefreshSessionRequestError(
      `Refresh session request failed with status ${response.status}.`,
    );
  }

  let envelope: unknown;
  try {
    envelope = await response.json();
  } catch {
    throw new RefreshSessionRequestError("Refresh session response was not valid JSON.");
  }

  if (
    typeof envelope !== "object" ||
    envelope === null ||
    (envelope as { isSuccess?: unknown }).isSuccess !== true ||
    !isRefreshSessionResult((envelope as { data?: unknown }).data)
  ) {
    throw new RefreshSessionRequestError("Refresh session response had an invalid envelope.");
  }

  return (envelope as { data: RefreshSessionResult }).data;
};
