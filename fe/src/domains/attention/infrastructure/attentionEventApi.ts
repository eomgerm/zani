import type { AttentionLabel } from "../domain/attentionPrediction";

/**
 * 서버가 받는 참여 상태. 백엔드 `AttentionState` 와 같은 이름을 쓴다.
 *
 * <p>여기서 보낼 수 있는 값은 `GOOD` 뿐이다. `CONFUSED`·`MISSED`·`NON_RESPONSE` 는 AI 1·2단계가 3연속으로 나온 뒤 **이해 확인 프롬프트 응답**으로 갈라지는 값이고,
 * `UNMEASURABLE` 은 판단 불가 3연속, `CAMERA_OFF` 는 카메라 조작에서 나온다. 그 흐름들은 아직 없어서 이 모듈이 만들어 낼 수 없다 — 규칙을 지어내면 서버의 코칭 트리거가 틀린 근거로
 * 돈다.
 */
export type AttentionEventType = "GOOD";

/** AI 3·4단계(`Engaged`·`Highly-Engaged`)만 `GOOD` 이다. 1·2단계는 프롬프트 없이 상태를 정할 수 없다. */
export const attentionEventTypeOf = (label: AttentionLabel): AttentionEventType | null =>
  label === "Engaged" || label === "Highly-Engaged" ? "GOOD" : null;

export type AttentionEvent = {
  type: AttentionEventType;
  /** 판정 창 시작·종료 시각(UTC ISO). */
  startedAt: string;
  endedAt: string;
  durationSec: number;
  /** 유효 프레임 비율(0~1). */
  signalQuality: number;
  /** 재시도해도 중복 반영되지 않도록 클라이언트가 만드는 식별자. */
  clientEventId: string;
};

export class AttentionEventRequestError extends Error {
  /** HTTP 상태. 응답을 받지 못했으면 0. */
  readonly status: number;

  constructor(message: string, status: number) {
    super(message);
    this.name = "AttentionEventRequestError";
    this.status = status;
  }
}

export type AttentionEventReporter = (
  sessionId: string,
  event: AttentionEvent,
  accessToken: string,
  signal?: AbortSignal,
) => Promise<void>;

/**
 * 참여도 판정 1건을 서버에 보고한다(POST /api/v1/sessions/{sessionId}/attention-events).
 *
 * <p>**판정 결과만 보낸다.** 프레임·랜드마크·blendshape 는 브라우저를 벗어나지 않는다. 서버도 계약에 없는 필드가 실려 오면 요청을 거절하므로, 여기서 보내는 형태를 늘릴 때는 서버
 * 계약을 먼저 확인해야 한다.
 *
 * <p>실패는 삼키지 않고 던진다. 다만 호출자는 이걸로 수업을 끊지 않는다 — 판정 보고가 안 되는 것과 수업이 안 되는 것은 다른 문제다.
 */
export const reportAttentionEvent: AttentionEventReporter = async (
  sessionId,
  event,
  accessToken,
  signal,
) => {
  const apiBaseUrl = (process.env.NEXT_PUBLIC_API_BASE_URL ?? "").replace(/\/$/, "");
  const response = await fetch(
    `${apiBaseUrl}/api/v1/sessions/${encodeURIComponent(sessionId)}/attention-events`,
    {
      method: "POST",
      headers: {
        Accept: "application/json",
        "Content-Type": "application/json",
        Authorization: `Bearer ${accessToken}`,
      },
      credentials: "include",
      body: JSON.stringify(event),
      signal,
    },
  );

  if (!response.ok) {
    throw new AttentionEventRequestError(
      `Attention event failed with status ${response.status}.`,
      response.status,
    );
  }
};
