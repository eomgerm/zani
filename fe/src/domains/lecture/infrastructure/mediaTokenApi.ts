export type MediaToken = {
  liveKitUrl: string;
  accessToken: string;
  roomName: string;
  participantIdentity: string;
  /** LiveKit 토큰 만료 시각(TTL 10분). */
  expiresAt: string;
  /** 최대 수업 시간(3시간)에 도달해 수업이 자동 종료될 시각. 강의실 종료 임박 안내의 기준이다. */
  sessionExpiresAt: string;
};

export type MediaTokenRequester = (
  sessionId: string,
  accessToken: string,
  signal?: AbortSignal,
) => Promise<MediaToken>;

export class MediaTokenRequestError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "MediaTokenRequestError";
  }
}

const isMediaToken = (value: unknown): value is MediaToken => {
  if (typeof value !== "object" || value === null) {
    return false;
  }

  const token = value as Record<string, unknown>;
  const isNonBlankString = (field: unknown) =>
    typeof field === "string" && field.trim().length > 0;
  return (
    isNonBlankString(token.liveKitUrl) &&
    isNonBlankString(token.accessToken) &&
    isNonBlankString(token.roomName) &&
    isNonBlankString(token.participantIdentity) &&
    isNonBlankString(token.expiresAt) &&
    isNonBlankString(token.sessionExpiresAt)
  );
};

/**
 * LiveKit 접속 토큰을 발급받는다(POST /api/v1/sessions/{sessionId}/media-token).
 *
 * <p>서버는 Bearer Access Token 으로 사용자를 판별한다. 쿠키에는 refresh 토큰만 있고 그마저 {@code /auth/refresh} 경로 전용이라, 헤더를 빼면 무조건 401 이
 * 되어 강의실이 LiveKit 에 붙지 못한다.
 *
 * <p>토큰은 인증 컨텍스트(useAuth)가 메모리에만 들고 있는 값을 그대로 전달받는다(저장·로그 금지).
 */
export const requestMediaToken: MediaTokenRequester = async (sessionId, accessToken, signal) => {
  const apiBaseUrl = (process.env.NEXT_PUBLIC_API_BASE_URL ?? "").replace(/\/$/, "");
  const response = await fetch(
    `${apiBaseUrl}/api/v1/sessions/${encodeURIComponent(sessionId)}/media-token`,
    {
      method: "POST",
      headers: { Accept: "application/json", Authorization: `Bearer ${accessToken}` },
      credentials: "include",
      signal,
    },
  );

  if (!response.ok) {
    throw new MediaTokenRequestError(
      `Media token request failed with status ${response.status}.`,
    );
  }

  let envelope: unknown;
  try {
    envelope = await response.json();
  } catch {
    throw new MediaTokenRequestError("Media token response was not valid JSON.");
  }

  if (
    typeof envelope !== "object" ||
    envelope === null ||
    (envelope as { isSuccess?: unknown }).isSuccess !== true ||
    !isMediaToken((envelope as { data?: unknown }).data)
  ) {
    throw new MediaTokenRequestError("Media token response had an invalid envelope.");
  }

  return (envelope as { data: MediaToken }).data;
};
