export type UpdateDisplayNameResult = {
  /** 변경 후 표시 이름. 서버가 앞뒤 공백을 다듬으므로 보낸 값과 다를 수 있다. */
  displayName: string;
};

export class UpdateDisplayNameRequestError extends Error {
  /** HTTP 상태. 응답을 받지 못했으면 0. */
  readonly status: number;

  constructor(message: string, status: number) {
    super(message);
    this.name = "UpdateDisplayNameRequestError";
    this.status = status;
  }
}

export type DisplayNameUpdater = (
  accessToken: string,
  displayName: string,
  signal?: AbortSignal,
) => Promise<UpdateDisplayNameResult>;

const isUpdateDisplayNameResult = (value: unknown): value is UpdateDisplayNameResult => {
  if (typeof value !== "object" || value === null) {
    return false;
  }
  return typeof (value as Record<string, unknown>).displayName === "string";
};

/**
 * 표시 이름을 바꾼다(PATCH /api/v1/members/me/display-name).
 * 서버는 Access Token 의 사용자 본인 이름만 바꾸므로 대상 회원을 따로 넘기지 않는다.
 * 토큰은 인증 컨텍스트(useAuth)가 메모리에만 들고 있는 값을 그대로 전달받는다(저장·로그 금지).
 */
export const updateDisplayName: DisplayNameUpdater = async (accessToken, displayName, signal) => {
  const apiBaseUrl = (process.env.NEXT_PUBLIC_API_BASE_URL ?? "").replace(/\/$/, "");
  const response = await fetch(`${apiBaseUrl}/api/v1/members/me/display-name`, {
    method: "PATCH",
    headers: {
      "Content-Type": "application/json",
      Accept: "application/json",
      Authorization: `Bearer ${accessToken}`,
    },
    credentials: "include",
    body: JSON.stringify({ displayName }),
    signal,
  });

  if (!response.ok) {
    throw new UpdateDisplayNameRequestError(
      `Update display name failed with status ${response.status}.`,
      response.status,
    );
  }

  let envelope: unknown;
  try {
    envelope = await response.json();
  } catch {
    throw new UpdateDisplayNameRequestError(
      "Update display name response was not valid JSON.",
      response.status,
    );
  }

  if (
    typeof envelope !== "object" ||
    envelope === null ||
    (envelope as { isSuccess?: unknown }).isSuccess !== true ||
    !isUpdateDisplayNameResult((envelope as { data?: unknown }).data)
  ) {
    throw new UpdateDisplayNameRequestError(
      "Update display name response had an invalid envelope.",
      response.status,
    );
  }

  return (envelope as { data: UpdateDisplayNameResult }).data;
};
