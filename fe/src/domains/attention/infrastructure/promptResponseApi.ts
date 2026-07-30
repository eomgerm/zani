/** 백엔드 `PromptKind` 와 같은 값. 이 브랜치는 이해 확인만 쓰고 나머지는 각 프롬프트 구현 때 붙인다. */
export type PromptKind = "UNDERSTANDING_CHECK" | "POSTURE_GUIDE" | "CAMERA_CHECK";

/**
 * 이해 확인 프롬프트에 실을 수 있는 답(기준 문서 §6). 학생이 고르는 3종에 더해, 30초가 지나
 * 자동으로 닫힌 경우의 `NON_RESPONSE` 가 있다 — 보내지 않으면 서버가 "학생이 무시함"과
 * "브라우저가 죽음"을 구분할 수 없다.
 *
 * 표기는 `NON_RESPONSE` 로 enum·JSON·DB 를 통일했다(기준 문서 §6). 백엔드 `PromptAnswer` 는
 * 이 값만 받고 별칭이 없어, 옛 표기로 보내면 400 으로 거절되고 응답 행이 아예 생기지 않는다.
 */
export type PromptAnswer = "OK" | "CONFUSED" | "MISSED" | "NON_RESPONSE";

/** 서버가 계약 밖 필드를 400 으로 거절하므로 이 네 값만 실어 보낸다. */
export interface PromptResponsePayload {
  readonly kind: PromptKind;
  readonly answer: PromptAnswer;
  /** 프롬프트를 띄운 시각(UTC ISO). 서버가 같은 프롬프트인지 알아보는 기준이라 재시도해도 같은 값을 보낸다. */
  readonly shownAt: string;
  /** 학생이 답한 시각(UTC ISO). 무응답이면 패널이 자동으로 닫힌 시각. */
  readonly respondedAt: string;
}

export type PromptResponseSender = (
  sessionId: string,
  promptId: string,
  payload: PromptResponsePayload,
  accessToken: string,
  signal?: AbortSignal,
) => Promise<void>;

export class PromptResponseSendError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "PromptResponseSendError";
  }
}

/**
 * 서버는 Bearer Access Token 으로 요청자가 이 세션의 학생인지 판단한다. 쿠키에는 refresh
 * 토큰만 있고 그마저 `/auth/refresh` 전용이라, 헤더를 빼면 무조건 401 이 되어 응답이 기록되지 않는다.
 */
export const sendPromptResponse: PromptResponseSender = async (
  sessionId,
  promptId,
  payload,
  accessToken,
  signal,
) => {
  const apiBaseUrl = (process.env.NEXT_PUBLIC_API_BASE_URL ?? "").replace(/\/$/, "");
  const response = await fetch(
    `${apiBaseUrl}/api/v1/sessions/${encodeURIComponent(sessionId)}/prompts/${encodeURIComponent(promptId)}/responses`,
    {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Accept: "application/json",
        Authorization: `Bearer ${accessToken}`,
      },
      credentials: "include",
      body: JSON.stringify(payload),
      signal,
    },
  );

  if (!response.ok) {
    throw new PromptResponseSendError(
      `Prompt response request failed with status ${response.status}.`,
    );
  }
};
