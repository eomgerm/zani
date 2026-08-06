"use client";

import { useEffect, useRef, useState } from "react";
import { CloseIcon } from "@/shared/ui";

/**
 * "복사됨" 을 보여주는 시간(ms).
 *
 * <p>복사됐다는 것만 알리고 곧 원래 문구로 돌아온다 — 링크를 여러 곳에 붙여 넣는 중이라면 다시 눌러야
 * 하는데, 버튼이 "복사됨" 인 채로 남아 있으면 이미 끝난 버튼처럼 보인다.
 */
const COPIED_FEEDBACK_MS = 1_600;

type InviteStudentsPromptProps = {
  /** 학생에게 전달할 입장 링크. 아직 모르면 null — 자리만 잡고 기다린다. */
  inviteUrl: string | null;
  onClose: () => void;
};

/**
 * 강사가 강의실에 들어오면 뜨는 초대 안내.
 *
 * <p>저절로 사라지지 않는다. 링크를 아직 전달하지 못했는데 안내가 먼저 사라지면 강사는 학생이 왜
 * 안 들어오는지 알 방법이 없다 — 닫는 것은 강사가 정한다.
 */
export function InviteStudentsPrompt({ inviteUrl, onClose }: InviteStudentsPromptProps) {
  const [copied, setCopied] = useState(false);
  const revertTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  // 안내가 닫히거나 화면을 떠날 때 남은 타이머를 정리한다.
  useEffect(
    () => () => {
      if (revertTimer.current !== null) clearTimeout(revertTimer.current);
    },
    [],
  );

  const copy = async () => {
    if (inviteUrl === null) return;
    try {
      await navigator.clipboard.writeText(inviteUrl);
      setCopied(true);
      // 연달아 누르면 앞의 타이머를 버린다. 그러지 않으면 먼저 걸린 것이 먼저 끝나 방금 누른
      // 피드백이 곧바로 사라진다.
      if (revertTimer.current !== null) clearTimeout(revertTimer.current);
      revertTimer.current = setTimeout(() => setCopied(false), COPIED_FEEDBACK_MS);
    } catch {
      // 클립보드 권한이 없거나 보안 컨텍스트가 아니면 실패한다. 링크는 필드에 그대로 보이므로
      // 직접 선택해 복사할 수 있다 — 실패를 알리되 길을 막지 않는다.
      setCopied(false);
    }
  };

  return (
    /* 학생 프롬프트(CoachingPromptPanel)와 같은 흰 카드다 — 어두운 강의실 위에 뜨는 안내는
       모두 같은 표면이어야 무엇이 "말을 거는 창" 인지 한눈에 갈린다. 자리만 좌하단으로 둔다. */
    <div
      role="dialog"
      aria-labelledby="invite-students-title"
      data-testid="invite-students-prompt"
      className="absolute bottom-24 left-5 z-50 w-[420px] max-w-[calc(100%-40px)] animate-[zPop_.2s] rounded-[20px] bg-surface p-[22px] text-ink shadow-[0_20px_50px_#0008]"
    >
      <button
        type="button"
        onClick={onClose}
        aria-label="초대 안내 닫기"
        className="absolute right-3.5 top-3.5 flex size-7 cursor-pointer items-center justify-center rounded-lg border-0 bg-transparent text-ink-fainter transition-colors hover:bg-primary-softer hover:text-ink"
      >
        <CloseIcon />
      </button>

      <h2 id="invite-students-title" className="mb-1.5 pr-8 text-base font-extrabold">
        학생을 초대하세요
      </h2>
      <p className="mb-4 text-sm text-ink-sub">강의 링크를 복사하여 학생들에게 전달해주세요.</p>

      <div className="flex items-center gap-2.5">
        {/* 읽기 전용이지만 선택은 되게 둔다 — 클립보드가 막힌 환경에서 직접 긁어 갈 수 있어야 한다. */}
        <input
          readOnly
          value={inviteUrl ?? "링크를 불러오는 중이에요"}
          aria-label="강의 초대 링크"
          onFocus={(event) => event.currentTarget.select()}
          className="z-input min-w-0 flex-1 font-mono text-[12.5px]"
        />
        {/* 폭을 고정한다. "복사하기" 와 "복사됨" 은 글자 수가 달라, 폭을 내용에 맡기면 누르는 순간
            버튼이 줄고 링크 필드가 늘어나 화면이 흔들린다. */}
        <button
          type="button"
          onClick={() => void copy()}
          disabled={inviteUrl === null}
          className="z-btn z-btn-primary w-[88px] shrink-0 whitespace-nowrap rounded-xl py-3 text-sm disabled:cursor-not-allowed disabled:opacity-50"
        >
          {copied ? "복사됨" : "복사하기"}
        </button>
      </div>
    </div>
  );
}
