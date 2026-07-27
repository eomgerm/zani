"use client";

import { useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";

const INVITE_LINK = "https://zani.app/live/ZANI-8KQ";

/**
 * 강의실 만들기 설정. 강의명 입력 + 초대 링크 확인 후 방을 생성한다.
 * 복사/생성은 시연용 로컬 동작이며, 생성 시 강의실로 이동한다.
 */
export function CreateSetupScreen() {
  const router = useRouter();
  const [title, setTitle] = useState("");
  const [copied, setCopied] = useState(false);

  const copy = () => {
    navigator.clipboard?.writeText(INVITE_LINK).catch(() => {});
    setCopied(true);
    setTimeout(() => setCopied(false), 1500);
  };

  return (
    <div className="flex min-h-screen items-center justify-center bg-canvas p-6">
      <div className="w-full max-w-[560px]">
        <div className="mb-[18px] flex items-center gap-2.5">
          <Link href="/home" className="z-btn size-[38px] rounded-xl border border-line-muted bg-surface text-base text-ink">
            ←
          </Link>
          <div>
            <div className="text-xl font-extrabold">강의실 만들기</div>
            <div className="text-[13px] text-ink-faint">강의 정보를 확인하고 방을 만들어 보세요.</div>
          </div>
        </div>

        <div className="flex flex-col gap-5 rounded-[22px] border border-line bg-surface px-7 py-[26px] shadow-soft">
          <div>
            <label className="mb-2 block text-[13px] font-bold text-ink-faint">강의명</label>
            <input
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              placeholder="예) JavaScript 기초 1강"
              className="z-input px-[15px] py-[13px]"
            />
          </div>

          <div>
            <label className="mb-2 block text-[13px] font-bold text-ink-faint">강의 초대 링크</label>
            <div className="flex gap-2.5">
              <div className="min-w-0 flex-1 truncate rounded-xl border border-line-light bg-muted-surface px-[15px] py-[13px] text-sm text-ink-muted">
                {INVITE_LINK}
              </div>
              <button
                onClick={copy}
                className="z-btn whitespace-nowrap rounded-xl border border-line-primary bg-primary-soft px-5 py-[13px] text-sm text-primary"
              >
                {copied ? "복사됨" : "복사"}
              </button>
            </div>
            <div className="mt-[7px] text-xs text-ink-ghost">
              참가자에게 이 링크를 공유하면 강의실에 참여할 수 있어요.
            </div>
          </div>
        </div>

        <div className="mt-[18px] flex gap-3">
          <Link href="/home" className="z-btn z-btn-outline z-btn-lg flex-1">
            취소
          </Link>
          <button onClick={() => router.push("/room/new")} className="z-btn z-btn-primary z-btn-lg flex-[2]">
            방 만들고 시작하기
          </button>
        </div>
      </div>
    </div>
  );
}
