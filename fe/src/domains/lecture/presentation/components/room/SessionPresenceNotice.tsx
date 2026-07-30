"use client";

import type { SessionPresenceState } from "../../useSessionPresence";

type SessionPresenceNoticeProps = Pick<
  SessionPresenceState,
  "reconnectStatus" | "sessionEnded" | "error"
> & {
  /** 지금 바로 강의실을 떠난다. 자동 이동을 기다리지 않으려는 사용자를 위한 출구다. */
  onLeave?: () => void;
};

/**
 * presence heartbeat 응답을 화면에 반영한다. 수업이 끝나면 그 사실과 나가는 방법을 알리고,
 * 강사 유예(GRACE_PERIOD) 중에는 자동 종료가 임박했음을 알린다. 그 밖에는 아무것도 그리지 않는다.
 */
export function SessionPresenceNotice({
  reconnectStatus,
  sessionEnded,
  error,
  onLeave,
}: SessionPresenceNoticeProps) {
  if (sessionEnded) {
    return (
      <div
        role="alert"
        data-testid="presence-session-ended"
        className="flex items-center justify-center gap-3 bg-danger-softer px-4 py-2 text-sm font-bold text-danger"
      >
        {/*
          이유는 heartbeat 응답이 알려줄 때만 구분한다. 강사가 직접 종료한 경우엔 다음 요청이
          409 로 막혀 본문이 없으므로, 그때는 왜 끝났는지 단정하지 않고 끝났다는 사실만 말한다.
        */}
        <span>
          {reconnectStatus === "SESSION_ENDED"
            ? "강사가 복귀하지 않아 수업이 종료되었습니다."
            : "수업이 종료되었습니다."}
        </span>
        <span className="font-semibold">잠시 후 강의실에서 나갑니다.</span>
        {onLeave !== undefined && (
          <button
            type="button"
            data-testid="presence-leave-now"
            onClick={onLeave}
            className="cursor-pointer rounded-lg bg-danger px-3 py-1 font-bold text-surface"
          >
            지금 나가기
          </button>
        )}
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
