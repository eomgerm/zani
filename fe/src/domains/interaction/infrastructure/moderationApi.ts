/**
 * 강사 제어 REST 클라이언트.
 *
 * 손들기·반응이 STOMP 인 것과 다르다 — 제어는 요청한 사람이 결과를 알아야 한다(권한 없음·대상 없음·
 * 미디어 서버 장애). 응답이 곧 결과인 REST 가 이 성격에 맞고, 결과 전파는 서버가 STOMP 로 따로 한다.
 */

export type MuteResult = {
  /** 이 호출 뒤 대상이 음소거 상태인지. */
  muted: boolean;
  /** 이미 음소거였는지(같은 요청의 재시도). 성공으로 다룬다. */
  alreadyMuted: boolean;
};

export class ModerationRequestError extends Error {
  /** HTTP 상태. 응답을 받지 못했으면 0. */
  readonly status: number;

  constructor(message: string, status: number) {
    super(message);
    this.name = "ModerationRequestError";
    this.status = status;
  }
}

export type ParticipantMuter = (
  sessionId: string,
  targetParticipantId: string,
  accessToken: string,
  signal?: AbortSignal,
) => Promise<MuteResult>;

const isMuteResult = (value: unknown): value is MuteResult => {
  if (typeof value !== "object" || value === null) return false;
  const result = value as Record<string, unknown>;
  return typeof result.muted === "boolean" && typeof result.alreadyMuted === "boolean";
};

/**
 * 학생 한 명을 강제 음소거한다(POST /api/v1/sessions/{sessionId}/moderation).
 *
 * 권한은 서버가 최종 판단한다 — 화면의 역할 표시를 믿지 않는다. 해제는 제공하지 않으므로 `action` 은
 * `MUTE` 뿐이고, 서버도 다른 값을 받지 않는다.
 */
export const muteParticipant: ParticipantMuter = async (
  sessionId,
  targetParticipantId,
  accessToken,
  signal,
) => {
  const apiBaseUrl = (process.env.NEXT_PUBLIC_API_BASE_URL ?? "").replace(/\/$/, "");
  const response = await fetch(
    `${apiBaseUrl}/api/v1/sessions/${encodeURIComponent(sessionId)}/moderation`,
    {
      method: "POST",
      headers: {
        Accept: "application/json",
        "Content-Type": "application/json",
        Authorization: `Bearer ${accessToken}`,
      },
      credentials: "include",
      // 참가자 ID 는 TSID 라 JS 안전 정수를 넘는다. 문자열로 보내야 값이 깨지지 않는다.
      body: JSON.stringify({ action: "MUTE", targetParticipantId }),
      signal,
    },
  );

  if (!response.ok) {
    throw new ModerationRequestError(
      `Mute failed with status ${response.status}.`,
      response.status,
    );
  }

  let envelope: unknown;
  try {
    envelope = await response.json();
  } catch {
    throw new ModerationRequestError("Mute response was not valid JSON.", response.status);
  }

  if (
    typeof envelope !== "object" ||
    envelope === null ||
    (envelope as { isSuccess?: unknown }).isSuccess !== true ||
    !isMuteResult((envelope as { data?: unknown }).data)
  ) {
    throw new ModerationRequestError("Mute response had an invalid envelope.", response.status);
  }

  return (envelope as { data: MuteResult }).data;
};
