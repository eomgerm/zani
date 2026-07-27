"use client";

import { useCallback, useRef, useState } from "react";
import { useRouter } from "next/navigation";

import { useAuth } from "@/domains/auth";
import { endSession, EndSessionRequestError, type SessionEnder } from "../../../infrastructure/endSessionApi";

type EndSessionButtonProps = {
  sessionId: string;
  /** 종료 성공 후 이동할 경로. 강사는 사후 메모 작성으로 이어진다. */
  redirectTo?: string;
  /** 종료 어댑터. 테스트에서 대체한다. */
  endSessionRequest?: SessionEnder;
};

const FORBIDDEN_MESSAGE = "수업을 연 강사만 종료할 수 있습니다.";
const SIGNED_OUT_MESSAGE = "로그인이 풀렸습니다. 다시 로그인한 뒤 종료해 주세요.";
const FAILURE_MESSAGE = "수업을 종료하지 못했습니다. 잠시 후 다시 시도해 주세요.";

function failureMessage(error: unknown): string {
  return error instanceof EndSessionRequestError && error.status === 403
    ? FORBIDDEN_MESSAGE
    : FAILURE_MESSAGE;
}

/**
 * 강사가 수업을 끝내는 버튼. 모든 참가자에게 영향을 주는 되돌릴 수 없는 조작이라 한 번 더 확인받고 보낸다.
 * 종료에 성공하면 강의실을 떠나고, 실패 사유는 그 자리에서 알린다. 권한은 서버가 최종 판단한다.
 */
export function EndSessionButton({
  sessionId,
  redirectTo = "/home",
  endSessionRequest = endSession,
}: EndSessionButtonProps) {
  const router = useRouter();
  const { accessToken } = useAuth();
  const [confirming, setConfirming] = useState(false);
  const [ending, setEnding] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // 종료 후 화면 전환 중 중복 클릭을 막는다(라우팅 전까지 컴포넌트가 살아 있다).
  const requested = useRef(false);

  const confirm = useCallback(async () => {
    if (requested.current) {
      return;
    }
    if (!accessToken) {
      setConfirming(false);
      setError(SIGNED_OUT_MESSAGE);
      return;
    }
    requested.current = true;
    setEnding(true);
    setError(null);
    try {
      await endSessionRequest(sessionId, accessToken);
      router.push(redirectTo);
    } catch (failure) {
      requested.current = false;
      setEnding(false);
      setConfirming(false);
      setError(failureMessage(failure));
    }
  }, [accessToken, endSessionRequest, redirectTo, router, sessionId]);

  if (!confirming) {
    return (
      <div className="flex items-center gap-2">
        {error && (
          <span role="alert" data-testid="end-session-error" className="text-[12.5px] font-bold text-danger">
            {error}
          </span>
        )}
        <button
          type="button"
          data-testid="end-session-button"
          onClick={() => {
            setError(null);
            setConfirming(true);
          }}
          className="z-btn z-btn-danger cursor-pointer rounded-[11px] px-[18px] py-[9px] text-[13.5px]"
        >
          수업 종료
        </button>
      </div>
    );
  }

  return (
    <div
      role="group"
      aria-label="수업 종료 확인"
      className="flex items-center gap-2 rounded-[11px] border border-danger bg-danger-softer px-3 py-1.5"
    >
      <span className="text-[12.5px] font-bold text-danger">수업을 종료할까요? 모든 참가자가 나가게 됩니다.</span>
      <button
        type="button"
        data-testid="end-session-confirm"
        disabled={ending}
        onClick={() => void confirm()}
        className="cursor-pointer rounded-lg bg-danger px-3 py-1 text-[12.5px] font-bold text-surface disabled:cursor-not-allowed disabled:opacity-60"
      >
        {ending ? "종료 중" : "종료"}
      </button>
      <button
        type="button"
        data-testid="end-session-cancel"
        disabled={ending}
        onClick={() => setConfirming(false)}
        className="cursor-pointer rounded-lg border border-line-muted bg-surface px-3 py-1 text-[12.5px] font-bold text-ink-sub disabled:cursor-not-allowed disabled:opacity-60"
      >
        취소
      </button>
    </div>
  );
}
