/**
 * 활성 화면 공유 슬롯을 서버와 조정한다. "한 번에 하나"(FRD §10.2)는 서버가 강제하므로, FE는 슬롯을 먼저 확보(claim)한
 * 뒤에만 LiveKit 화면 트랙을 publish 한다. "누가 공유 중인가"의 브로드캐스트는 LiveKit 트랙 시그널링이 담당하고, 이 API는
 * 슬롯 확보·반납만 맡는다.
 */

export class ScreenShareRequestError extends Error {
  /** HTTP 상태. 409면 다른 참가자가 이미 공유 중(또는 종료된 세션). 응답을 받지 못했으면 0. */
  readonly status: number;

  constructor(message: string, status: number) {
    super(message);
    this.name = "ScreenShareRequestError";
    this.status = status;
  }
}

export type ScreenShareClaimer = (
  sessionId: string,
  accessToken: string,
  signal?: AbortSignal,
) => Promise<void>;

export type ScreenShareReleaser = (
  sessionId: string,
  accessToken: string,
  signal?: AbortSignal,
) => Promise<void>;

const screenShareUrl = (sessionId: string) => {
  const apiBaseUrl = (process.env.NEXT_PUBLIC_API_BASE_URL ?? "").replace(/\/$/, "");
  return `${apiBaseUrl}/api/v1/sessions/${encodeURIComponent(sessionId)}/screen-share`;
};

/**
 * 화면 공유 슬롯을 확보하거나 갱신한다(POST). 공유를 시작하기 전과, 공유 중 TTL 갱신을 위해 주기적으로 호출한다.
 * 다른 참가자가 공유 중이면 409로 거부된다. 서버는 Bearer Access Token 으로 사용자를 판별한다(presence와 동일).
 */
export const claimScreenShare: ScreenShareClaimer = async (sessionId, accessToken, signal) => {
  const response = await fetch(screenShareUrl(sessionId), {
    method: "POST",
    headers: {
      Accept: "application/json",
      Authorization: `Bearer ${accessToken}`,
    },
    credentials: "include",
    signal,
  });

  if (!response.ok) {
    throw new ScreenShareRequestError(
      `Screen share claim failed with status ${response.status}.`,
      response.status,
    );
  }
};

/** 화면 공유 슬롯을 반납한다(DELETE). 공유 중지·강의실 퇴장 시 호출한다. 자기 슬롯일 때만 비워지므로 멱등하다. */
export const releaseScreenShare: ScreenShareReleaser = async (sessionId, accessToken, signal) => {
  const response = await fetch(screenShareUrl(sessionId), {
    method: "DELETE",
    headers: {
      Accept: "application/json",
      Authorization: `Bearer ${accessToken}`,
    },
    credentials: "include",
    signal,
  });

  if (!response.ok) {
    throw new ScreenShareRequestError(
      `Screen share release failed with status ${response.status}.`,
      response.status,
    );
  }
};
