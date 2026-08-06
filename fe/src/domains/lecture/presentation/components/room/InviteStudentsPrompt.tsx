"use client";

import { useState } from "react";
import { CloseIcon } from "@/shared/ui";

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

  const copy = async () => {
    if (inviteUrl === null) return;
    try {
      await navigator.clipboard.writeText(inviteUrl);
      setCopied(true);
    } catch {
      // 클립보드 권한이 없거나 보안 컨텍스트가 아니면 실패한다. 링크는 필드에 그대로 보이므로
      // 직접 선택해 복사할 수 있다 — 실패를 알리되 길을 막지 않는다.
      setCopied(false);
    }
  };

  return (
    <div
      role="dialog"
      aria-labelledby="invite-students-title"
      data-testid="invite-students-prompt"
      className="absolute right-5 top-5 z-50 w-[380px] max-w-[calc(100%-40px)] animate-[zPop_.18s] rounded-2xl border border-room-line bg-panel p-5 shadow-[0_18px_44px_rgba(0,0,0,.5)]"
    >
      <button
        type="button"
        onClick={onClose}
        aria-label="초대 안내 닫기"
        className="absolute right-3 top-3 flex size-7 cursor-pointer items-center justify-center rounded-lg border-0 bg-transparent text-panel-muted transition-colors hover:bg-room-control hover:text-panel-text"
      >
        <CloseIcon />
      </button>

      <h2 id="invite-students-title" className="mb-1.5 pr-8 text-[15px] font-extrabold">
        학생을 초대하세요
      </h2>
      <p className="mb-3.5 text-[13px] leading-[1.6] text-panel-soft">
        강의 링크를 복사하여 학생들에게 전달해주세요.
      </p>

      <div className="flex items-center gap-2">
        <button
          type="button"
          onClick={() => void copy()}
          disabled={inviteUrl === null}
          className="shrink-0 cursor-pointer whitespace-nowrap rounded-xl border-0 bg-primary px-3.5 py-2.5 text-[13px] font-extrabold text-white transition-colors hover:bg-primary-dark disabled:cursor-not-allowed disabled:opacity-50"
        >
          {copied ? "복사됨" : "복사하기"}
        </button>
        {/* 읽기 전용이지만 선택은 되게 둔다 — 클립보드가 막힌 환경에서 직접 긁어 갈 수 있어야 한다. */}
        <input
          readOnly
          value={inviteUrl ?? "링크를 불러오는 중이에요"}
          aria-label="강의 초대 링크"
          onFocus={(event) => event.currentTarget.select()}
          className="min-w-0 flex-1 rounded-xl border border-room-edge bg-room-input px-3 py-2.5 font-mono text-[12px] text-panel-text outline-none"
        />
      </div>
    </div>
  );
}
