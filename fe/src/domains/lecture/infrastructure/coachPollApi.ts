import type { CoachTipUnavailableReason } from "../domain/coachingAvailability";

/**
 * 강사 코칭 폴링 어댑터.
 *
 * 팁은 WebSocket 이 아니라 10초 REST 폴링으로 받는다(85 결정). 응답 하나에 대기 중인 팁과
 * 오디오 업로드 요청이 함께 실린다.
 *
 * <h3>⚠️ 85 회신 대기 중 — FE 가 가정한 계약이다</h3>
 *
 * 85 티켓의 "구체 경로는 이 티켓에서 정한다" 가 아직 비어 있어, FE 가 아래 형태를 가정하고
 * 먼저 구현했다. <b>204·85 를 맡으신 분께</b>: 실제 계약이 아래와 다르면 <b>이 파일만</b> 고치면
 * 된다. 판정 규칙(`coachingAvailability`)·배지·훅은 이 형태에 의존하지 않는다.
 *
 * <pre>
 * 경로                 GET /api/v1/sessions/{sessionId}/coach/poll
 * 팁 없을 때           200 + tip: null
 *                      (204 No Content 아님 — 사유·업로드 요청을 함께 실어야 한다)
 * triggerId            대기 중인 트리거가 없으면 null
 * tipType              CONFUSED_HIGH · MISSED_HIGH · CONFUSED_AND_MISSED ·
 *                      NON_RESPONSE_HIGH · UNMEASURABLE_HIGH
 * unavailableReason    NO_TRANSCRIPT · TRANSCRIPTION_FAILED · TIP_FAILED ·
 *                      LOW_CONFIDENCE · null
 * audioUploadRequest   형태를 해석하지 않고 그대로 통과시킨다(85 소유)
 * </pre>
 *
 * <b>FE 동작 참고</b>: 강사에게 표시하는 것은 `TRANSCRIPTION_FAILED` 와 `TIP_FAILED` 뿐이다.
 * `NO_TRANSCRIPT`(버퍼 60초 미만)와 `LOW_CONFIDENCE` 는 정상 동작이라 표시하지 않는다 —
 * 수업 시작 직후에는 버퍼가 늘 60초 미만이라 표시하면 매 수업 오류가 뜬다. 근거는
 * `coachingAvailability.ts` 에 있다.
 */

/** §7.6 이 고르는 팁 유형 5종. FE 는 화면을 분기하지 않고 검증·로깅에만 쓴다(86 결정). */
export type CoachTipType =
  | "CONFUSED_HIGH"
  | "MISSED_HIGH"
  | "CONFUSED_AND_MISSED"
  | "NON_RESPONSE_HIGH"
  | "UNMEASURABLE_HIGH";

/** 문구는 서버가 §8 템플릿으로 완성해 내려준다. FE 는 그대로 표시한다. */
export interface CoachTip {
  readonly tipType: CoachTipType;
  readonly title: string;
  readonly message: string;
  readonly targetConcept: string;
}

export interface CoachPollResult {
  /** 대기 중인 트리거가 없으면 null. 같은 값을 두 번 받아도 한 번만 표시해야 한다. */
  readonly triggerId: string | null;
  readonly tip: CoachTip | null;
  readonly unavailableReason: CoachTipUnavailableReason | null;
  /** 오디오 업로드 요청(85 소유). 팁과 별개로 처리하므로 형태를 해석하지 않고 그대로 넘긴다. */
  readonly audioUploadRequest: unknown | null;
}

export type CoachPoller = (sessionId: string, signal?: AbortSignal) => Promise<CoachPollResult>;

export class CoachPollError extends Error {
  /** HTTP 상태. 네트워크 실패 등 응답이 없으면 0. */
  readonly status: number;

  constructor(message: string, status: number) {
    super(message);
    this.name = "CoachPollError";
    this.status = status;
  }
}

const TIP_TYPES: readonly CoachTipType[] = [
  "CONFUSED_HIGH",
  "MISSED_HIGH",
  "CONFUSED_AND_MISSED",
  "NON_RESPONSE_HIGH",
  "UNMEASURABLE_HIGH",
];

const UNAVAILABLE_REASONS: readonly CoachTipUnavailableReason[] = [
  "NO_TRANSCRIPT",
  "TRANSCRIPTION_FAILED",
  "TIP_FAILED",
  "LOW_CONFIDENCE",
];

const isNonBlankString = (value: unknown): value is string =>
  typeof value === "string" && value.trim().length > 0;

/** 필수 필드가 하나라도 빠진 팁은 표시하지 않는다(86 요구사항). 통째로 버린다. */
const parseTip = (value: unknown): CoachTip | null => {
  if (typeof value !== "object" || value === null) {
    return null;
  }

  const tip = value as Record<string, unknown>;
  if (
    !TIP_TYPES.includes(tip.tipType as CoachTipType) ||
    !isNonBlankString(tip.title) ||
    !isNonBlankString(tip.message) ||
    !isNonBlankString(tip.targetConcept)
  ) {
    return null;
  }

  return {
    tipType: tip.tipType as CoachTipType,
    title: tip.title,
    message: tip.message,
    targetConcept: tip.targetConcept,
  };
};

export const pollCoach: CoachPoller = async (sessionId, signal) => {
  const apiBaseUrl = (process.env.NEXT_PUBLIC_API_BASE_URL ?? "").replace(/\/$/, "");

  let response: Response;
  try {
    response = await fetch(
      `${apiBaseUrl}/api/v1/sessions/${encodeURIComponent(sessionId)}/coach/poll`,
      {
        method: "GET",
        headers: { Accept: "application/json" },
        credentials: "include",
        signal,
      },
    );
  } catch (error) {
    // 네트워크 실패도 강사 입장에서는 코칭을 못 받는 것과 같다. 상태 0 으로 올린다.
    throw new CoachPollError(`Coach poll request failed: ${String(error)}`, 0);
  }

  if (!response.ok) {
    throw new CoachPollError(`Coach poll failed with status ${response.status}.`, response.status);
  }

  let envelope: unknown;
  try {
    envelope = await response.json();
  } catch {
    throw new CoachPollError("Coach poll response was not valid JSON.", response.status);
  }

  if (
    typeof envelope !== "object" ||
    envelope === null ||
    (envelope as { isSuccess?: unknown }).isSuccess !== true ||
    typeof (envelope as { data?: unknown }).data !== "object" ||
    (envelope as { data?: unknown }).data === null
  ) {
    throw new CoachPollError("Coach poll response had an invalid envelope.", response.status);
  }

  const data = (envelope as { data: Record<string, unknown> }).data;
  const reason = data.unavailableReason;

  return {
    triggerId: isNonBlankString(data.triggerId) ? data.triggerId : null,
    tip: parseTip(data.tip),
    unavailableReason: UNAVAILABLE_REASONS.includes(reason as CoachTipUnavailableReason)
      ? (reason as CoachTipUnavailableReason)
      : null,
    audioUploadRequest: data.audioUploadRequest ?? null,
  };
};
