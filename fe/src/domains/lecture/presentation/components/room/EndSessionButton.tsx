"use client";

import { useCallback, useRef, useState } from "react";
import { useRouter } from "next/navigation";

import { useAuth } from "@/domains/auth";
import { endSession, EndSessionRequestError, type SessionEnder } from "../../../infrastructure/endSessionApi";

type EndSessionConfirmProps = {
  sessionId: string;
  /** 종료 성공 후 이동할 경로. 강사는 사후 메모 작성으로 이어진다. */
  redirectTo?: string;
  /** 종료 어댑터. 테스트에서 대체한다. */
  endSessionRequest?: SessionEnder;
  /**
   * 종료 성공 직후 호출된다. 이미 {@link redirectTo} 화면에 있는 호출자(홈 배너 등)는 이동만으로는 화면이 갱신되지 않으므로, 이 콜백으로 목록을 다시 읽는다.
   */
  onEnded?: () => void;
  /** 종료하지 않고 물러난다. */
  onCancel: () => void;
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
 * 수업 종료 확인. 모든 참가자에게 영향을 주는 되돌릴 수 없는 조작이라 한 번 더 확인받고 보낸다.
 *
 * <p>수업은 여기서 끝난다 — 사후 메모를 저장할 때가 아니다. 그래서 확인을 누른 순간 종료 요청이 나가고, 그 뒤에 메모 화면으로 넘어간다. 메모를 쓰지 않고 창을 닫아도 수업은 이미 끝나 있다.
 *
 * <p>강의실 하단 바와 홈 배너가 같은 확인 UI 를 쓴다. 두 곳이 각자 종료를 구현하면 한쪽만 고쳐진다.
 */
export function EndSessionConfirm({
  sessionId,
  redirectTo = "/home",
  endSessionRequest = endSession,
  onEnded,
  onCancel,
}: EndSessionConfirmProps) {
  const router = useRouter();
  const { accessToken } = useAuth();
  const [ending, setEnding] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // 종료 후 화면 전환 중 중복 클릭을 막는다(라우팅 전까지 컴포넌트가 살아 있다).
  const requested = useRef(false);

  const confirm = useCallback(async () => {
    if (requested.current) {
      return;
    }
    if (!accessToken) {
      setError(SIGNED_OUT_MESSAGE);
      return;
    }
    requested.current = true;
    setEnding(true);
    setError(null);
    try {
      await endSessionRequest(sessionId, accessToken);
      onEnded?.();
      router.push(redirectTo);
    } catch (failure) {
      requested.current = false;
      setEnding(false);
      setError(failureMessage(failure));
    }
  }, [accessToken, endSessionRequest, onEnded, redirectTo, router, sessionId]);

  return (
    <div
      role="group"
      aria-label="수업 종료 확인"
      className="flex items-center gap-2 rounded-[11px] border border-danger bg-danger-softer px-3 py-1.5"
    >
      {/* 실패하면 말풍선을 닫지 않고 원인을 그 자리에 띄운다. 닫아버리면 왜 안 끝났는지 모른 채 다시 눌러야 한다. */}
      {error === null ? (
        <span className="whitespace-nowrap text-[12.5px] font-bold text-danger">
          수업을 종료할까요? 모든 참가자가 나가게 됩니다.
        </span>
      ) : (
        <span
          role="alert"
          data-testid="end-session-error"
          className="whitespace-nowrap text-[12.5px] font-bold text-danger"
        >
          {error}
        </span>
      )}
      <button
        type="button"
        data-testid="end-session-confirm"
        disabled={ending}
        onClick={() => void confirm()}
        className="cursor-pointer whitespace-nowrap rounded-lg bg-danger px-3 py-1 text-[12.5px] font-bold text-surface disabled:cursor-not-allowed disabled:opacity-60"
      >
        {ending ? "종료 중" : "종료"}
      </button>
      <button
        type="button"
        data-testid="end-session-cancel"
        disabled={ending}
        onClick={onCancel}
        className="cursor-pointer whitespace-nowrap rounded-lg border border-line-muted bg-surface px-3 py-1 text-[12.5px] font-bold text-ink-sub disabled:cursor-not-allowed disabled:opacity-60"
      >
        취소
      </button>
    </div>
  );
}

type EndSessionButtonProps = Omit<EndSessionConfirmProps, "onCancel">;

/**
 * "수업 종료" 버튼과 확인 말풍선을 함께 묶은 형태. 홈 배너처럼 전용 트리거가 필요한 곳에서 쓴다.
 *
 * <p>강의실에서는 쓰지 않는다 — 거기서는 나가기 버튼이 트리거를 겸하고 {@link EndSessionConfirm} 만 말풍선으로 띄운다. 나가기와 수업 종료가 따로 있으면 강사가 어느 쪽이 수업을
 * 끝내는 버튼인지 헷갈린다.
 */
export function EndSessionButton(props: EndSessionButtonProps) {
  const [confirming, setConfirming] = useState(false);

  if (confirming) {
    return <EndSessionConfirm {...props} onCancel={() => setConfirming(false)} />;
  }

  return (
    <button
      type="button"
      data-testid="end-session-button"
      onClick={() => setConfirming(true)}
      className="z-btn z-btn-danger cursor-pointer rounded-[11px] px-[18px] py-[9px] text-[13.5px]"
    >
      수업 종료
    </button>
  );
}
