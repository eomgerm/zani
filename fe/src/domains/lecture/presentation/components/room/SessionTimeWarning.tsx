"use client";

import { useSessionTimeWarning } from "../../useSessionTimeWarning";

type SessionTimeWarningProps = {
  /** 서버가 알려준 종료 예정 시각(ISO-8601). 없으면 아무 것도 표시하지 않는다. */
  expiresAt?: string;
};

function formatRemaining(remainingMs: number): string {
  const totalSeconds = Math.ceil(remainingMs / 1000);
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${minutes}:${String(seconds).padStart(2, "0")}`;
}

/**
 * 최대 수업 시간(3시간) 종료가 임박했을 때 알리는 배너. 남은 시간이 경고 임계값(useSessionTimeWarning의
 * WARNING_THRESHOLD_MINUTES) 이하로 떨어지면 나타나고, 종료 예정 시각이 지나면 곧 종료된다는 안내로 바뀐다.
 * 실제 종료는 서버 스케줄러가 수행한다.
 */
export function SessionTimeWarning({ expiresAt }: SessionTimeWarningProps) {
  const { remainingMs, warning, expired } = useSessionTimeWarning(expiresAt);

  if (!warning && !expired) {
    return null;
  }

  return (
    <div
      role="status"
      aria-live="polite"
      className="flex items-center justify-center gap-2 rounded-lg bg-amber-50 px-4 py-2 text-sm text-amber-900"
    >
      {expired ? (
        <span>최대 수업 시간에 도달해 곧 자동으로 종료됩니다.</span>
      ) : (
        <span>
          최대 수업 시간까지 <strong>{formatRemaining(remainingMs)}</strong> 남았습니다. 시간이 지나면 자동으로
          종료됩니다.
        </span>
      )}
    </div>
  );
}
