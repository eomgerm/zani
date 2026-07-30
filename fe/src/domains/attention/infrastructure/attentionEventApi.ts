import { ATTENTION_FEATURE_SCHEMA } from "../domain/attentionFeatureSchema";
import type { DetectorReport } from "../domain/detectionOutcome";

/**
 * 참여도 관측 전송 어댑터(POST /api/v1/sessions/{sessionId}/attention-events).
 *
 * 보내는 값은 **관측**이지 학생 상태가 아니다. 집계에 쓰는 상태 6종은 서버가 이 관측과 프롬프트
 * 응답을 합쳐 파생한다. 상태가 바뀌지 않아도 10초마다 계속 보낸다 — 끊기면 만료로 드러나야
 * 서버가 "브라우저가 죽음"과 "학생이 이탈함"을 구분할 수 있다.
 *
 * <p>이 어댑터는 상태를 갖지 않는다. 재시도·409 이후 중단은 보고 주기를 아는
 * `presentation/useAttentionEventReporter` 가 판단한다.
 */

export interface AttentionEventAck {
  /** 같은 `clientEventId` 로 이미 반영된 관측이라 서버가 중복으로 넘긴 경우. */
  readonly duplicate: boolean;
}

export type AttentionEventSender = (
  sessionId: string,
  report: DetectorReport,
  accessToken: string,
  signal?: AbortSignal,
) => Promise<AttentionEventAck>;

export class AttentionEventSendError extends Error {
  /** HTTP 상태. 네트워크 실패 등 응답이 없으면 0. */
  readonly status: number;

  constructor(message: string, status: number) {
    super(message);
    this.name = "AttentionEventSendError";
    this.status = status;
  }
}

/**
 * 서버가 받는 요청 본문. 계약에 있는 값만 담는다.
 *
 * <p>서버는 계약 밖 필드가 오면 400 으로 거절한다(`AttentionEventRequest.isFreeOfUnsupportedFields`).
 * 원본 프레임·랜드마크·4단계 확률값은 절대 실리지 않아야 하므로, 리포트를 펼쳐 보내지 않고 이
 * 타입의 필드를 하나씩 옮겨 담는다.
 */
interface AttentionEventRequestBody {
  readonly outcome: string;
  readonly observedAt: string;
  readonly windowStartedAt?: string;
  readonly featureSchemaVersion: string;
  readonly clientEventId: string;
}

/**
 * `signalQuality` 는 싣지 않는다. 계약상 선택이고 서버 집계에 읽는 곳이 없어(요청 스펙 주석)
 * 값을 만들어 낼 이유가 없다.
 */
const toRequestBody = (report: DetectorReport): AttentionEventRequestBody => ({
  outcome: report.outcome,
  observedAt: new Date(report.observedAtMs).toISOString(),
  // 창을 관측하지 않은 보고는 키 자체를 뺀다. 서버 시간선 검증이 읽을 값을 지어내지 않는다.
  ...(report.windowStartedAtMs === undefined
    ? {}
    : { windowStartedAt: new Date(report.windowStartedAtMs).toISOString() }),
  // 판정을 뽑은 특징 추출 계약. FE 에 이미 있는 배포 상수를 그대로 쓴다.
  featureSchemaVersion: ATTENTION_FEATURE_SCHEMA,
  clientEventId: report.clientEventId,
});

/**
 * 서버는 Bearer Access Token 으로 요청자가 이 세션의 학생인지 판단한다. 쿠키에는 refresh
 * 토큰만 있고 그마저 `/auth/refresh` 전용이라, 헤더를 빼면 무조건 401 이다.
 *
 * <p>실패는 상태 코드를 담은 오류로 올려 호출부가 재시도·중단을 판단하게 한다(409 는 종료된
 * 세션이라 다시 보내도 같은 답이 온다).
 */
export const sendAttentionEvent: AttentionEventSender = async (
  sessionId,
  report,
  accessToken,
  signal,
) => {
  const apiBaseUrl = (process.env.NEXT_PUBLIC_API_BASE_URL ?? "").replace(/\/$/, "");

  let response: Response;
  try {
    response = await fetch(
      `${apiBaseUrl}/api/v1/sessions/${encodeURIComponent(sessionId)}/attention-events`,
      {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Accept: "application/json",
          Authorization: `Bearer ${accessToken}`,
        },
        credentials: "include",
        body: JSON.stringify(toRequestBody(report)),
        signal,
      },
    );
  } catch (error) {
    // 응답이 없으면 서버가 받았는지 알 수 없다. 같은 clientEventId 로 재시도해도 안전하다.
    throw new AttentionEventSendError(`Attention event request failed: ${String(error)}`, 0);
  }

  if (!response.ok) {
    throw new AttentionEventSendError(
      `Attention event failed with status ${response.status}.`,
      response.status,
    );
  }

  // 서버는 이미 관측을 받아들였다. 본문이 계약과 달라도 전송을 실패로 되돌리지 않는다.
  try {
    const envelope: unknown = await response.json();
    const data = (envelope as { data?: { duplicate?: unknown } } | null)?.data;
    return { duplicate: data?.duplicate === true };
  } catch {
    return { duplicate: false };
  }
};
