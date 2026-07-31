"use client";

import Link from "next/link";

import type { SessionPresenceState } from "../../useSessionPresence";

type SessionPresenceNoticeProps = Pick<
  SessionPresenceState,
  "reconnectStatus" | "sessionEnded" | "error"
>;

/**
 * presence heartbeat 응답을 화면에 반영한다. 세션이 종료되면(강사가 방을 닫았거나, 미복귀로
 * 자동 종료됐거나, 이미 닫힌 방) 곧 강의실에서 나간다는 것을 알리고, 강사 유예(GRACE_PERIOD)
 * 중에는 자동 종료가 임박했음을 알린다. 그 밖에는 아무것도 그리지 않는다.
 *
 * 종료 뒤 실제 이동은 RoomScreen 이 잠깐 뒤에 처리한다. 여기 나가기 링크는 기다리지 않고
 * 바로 나가려는 참가자를 위한 것이라, 자동 이동과 같은 목적지(내 강의실)로 보낸다.
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
        <span>수업이 종료되었습니다. 잠시 후 강의실에서 나갑니다.</span>
        <Link href="/my-lectures" className="rounded-lg bg-danger px-3 py-1 font-bold text-surface">
          지금 나가기
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
