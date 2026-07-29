import type { CoachTipUnavailableReason } from "../domain/coachingAvailability";

/**
 * 강사 코칭 폴링 어댑터.
 *
 * 팁은 WebSocket 이 아니라 10초 REST 폴링으로 받는다(85 결정). 응답 하나에 대기 중인 팁과
 * 오디오 업로드 요청이 함께 실린다.
 *
 * <p>계약은 85 의 `CoachingTipResponse` 를 따른다. 팁이 없어도 200 에 본문을 담아 내려주므로
 * "지금 띄울 팁이 없는 것"과 "만들지 못한 것"을 구분할 수 있다.
 *
 * <pre>
 * 경로                 GET /api/v1/sessions/{sessionId}/coaching-tip
 * triggerId            대기 중인 트리거가 없으면 null
 * tipType              CONFUSED · MISSED · CONFUSED_AND_MISSED · NON_RESPONSE · UNMEASURABLE
 * unavailableReason    NO_TRANSCRIPT · TRANSCRIPTION_FAILED · TIP_GENERATION_FAILED ·
 *                      LOW_CONFIDENCE · null
 * </pre>
 *
 * <p><b>`unavailableReason` 은 배지에 쓰지 않는다.</b> 그 값이 있다고 코칭이 죽은 것이 아니라
 * 트리거 하나가 실패한 것이고 다음 트리거에서 회복된다(85 응답 계약 주석). 강사에게 알리는
 * 기준은 폴링 자체의 연속 실패이며 근거는 `coachingAvailability.ts` 에 있다. 여기서는 계약
 * 충실성과 로깅을 위해 값을 그대로 받아 둔다.
 */

/** §7.6 이 고르는 팁 유형 5종. FE 는 화면을 분기하지 않고 검증·로깅에만 쓴다(86 결정). */
export type CoachTipType =
  | "CONFUSED"
  | "MISSED"
  | "CONFUSED_AND_MISSED"
  | "NON_RESPONSE"
  | "UNMEASURABLE";

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
  "CONFUSED",
  "MISSED",
  "CONFUSED_AND_MISSED",
  "NON_RESPONSE",
  "UNMEASURABLE",
];

const UNAVAILABLE_REASONS: readonly CoachTipUnavailableReason[] = [
  "NO_TRANSCRIPT",
  "TRANSCRIPTION_FAILED",
  "TIP_GENERATION_FAILED",
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
      `${apiBaseUrl}/api/v1/sessions/${encodeURIComponent(sessionId)}/coaching-tip`,
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
  };
};
