"use client";

import Link from "next/link";

import type { SessionPresenceState } from "../../useSessionPresence";

type SessionPresenceNoticeProps = Pick<
  SessionPresenceState,
  "reconnectStatus" | "sessionEnded" | "error"
>;

/**
 * presence heartbeat 응답을 화면에 반영한다. 세션이 종료되면 나가기를 안내하고,
 * 강사 유예(GRACE_PERIOD) 중에는 자동 종료가 임박했음을 알린다. 그 밖에는 아무것도 그리지 않는다.
 */
export function SessionPresenceNotice({
  reconnectStatus,
  sessionEnded,
  error,
}: SessionPresenceNoticeProps) {
  if (sessionEnded) {
    return (
      <div
        role="alert"
        data-testid="presence-session-ended"
        className="flex items-center justify-center gap-3 bg-danger-softer px-4 py-2 text-sm font-bold text-danger"
      >
        <span>강사가 복귀하지 않아 수업이 종료되었습니다.</span>
        <Link href="/home" className="rounded-lg bg-danger px-3 py-1 font-bold text-surface">
          나가기
        </Link>
      </div>
    );
  }

  if (error) {
    return (
      <div
        role="alert"
        data-testid="presence-error"
        className="flex items-center justify-center bg-danger-softer px-4 py-2 text-sm font-bold text-danger"
      >
        {error}
      </div>
    );
  }

  if (reconnectStatus === "GRACE_PERIOD") {
    return (
      <div
        role="status"
        aria-live="polite"
        data-testid="presence-grace-period"
        className="flex items-center justify-center bg-warn-soft px-4 py-2 text-sm font-bold text-warn-text"
      >
        강사 연결이 끊겼습니다. 5분 안에 복귀하지 않으면 수업이 자동 종료됩니다.
      </div>
    );
  }

  return null;
}
