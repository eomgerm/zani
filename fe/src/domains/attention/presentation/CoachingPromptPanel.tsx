"use client";

import { useEffect, useRef } from "react";

export interface CoachingPromptOption<TValue extends string> {
  readonly value: TValue;
  readonly label: string;
  readonly emoji?: string;
  /** 버튼 톤(테두리·배경·글자색)을 나타내는 Tailwind 클래스. */
  readonly toneClassName: string;
}

export interface CoachingPromptPanelProps<TValue extends string> {
  readonly title: string;
  readonly body: string;
  readonly options: readonly CoachingPromptOption<TValue>[];
  readonly remainingMs: number;
  readonly durationMs: number;
  onSelect(value: TValue): void;
}

/**
 * 강의실 하단에 뜨는 확인 프롬프트 패널. 전체 화면을 가리지 않는 비모달 패널이며, 이해 확인·
 * 상세 안내·카메라 확인 세 프롬프트가 이 컴포넌트를 공유한다(옵션 구성만 다르다).
 *
 * 수업 흐름을 막지 않아야 하므로 포커스 트랩은 두지 않는다 — 마운트 시 첫 버튼으로 포커스만
 * 옮겨 스크린리더·키보드 사용자가 프롬프트 등장을 놓치지 않게 한다.
 */
export function CoachingPromptPanel<TValue extends string>({
  title,
  body,
  options,
  remainingMs,
  durationMs,
  onSelect,
}: CoachingPromptPanelProps<TValue>) {
  const firstButtonRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    firstButtonRef.current?.focus();
  }, []);

  const remainingSeconds = Math.ceil(remainingMs / 1000);
  const percentRemaining =
    durationMs === 0 ? 0 : Math.max(0, Math.min(100, (remainingMs / durationMs) * 100));

  return (
    <div
      role="group"
      aria-label={title}
      className="absolute bottom-24 left-1/2 z-50 w-[420px] -translate-x-1/2 animate-[zPop_.2s] rounded-[20px] bg-surface p-[22px] text-ink shadow-[0_20px_50px_#0008]"
    >
      <div className="mb-1.5 flex items-center justify-between">
        <span className="text-base font-extrabold">{title}</span>
        <span
          aria-live="polite"
          className="flex h-[34px] min-w-[34px] items-center justify-center rounded-[10px] bg-primary-soft px-2 font-mono text-[15px] font-black text-primary"
        >
          {remainingSeconds}
        </span>
      </div>
      <p className="mb-2 text-sm text-ink-sub">{body}</p>
      <div className="mb-4 h-1 overflow-hidden rounded-full bg-primary-softer">
        <div
          aria-hidden="true"
          className="h-full rounded-full bg-primary transition-[width] duration-100 ease-linear"
          style={{ width: `${percentRemaining}%` }}
        />
      </div>
      <div className="flex gap-2.5">
        {options.map((option, index) => (
          <button
            key={option.value}
            type="button"
            ref={index === 0 ? firstButtonRef : undefined}
            onClick={() => onSelect(option.value)}
            className={`z-btn flex-1 rounded-[14px] border-[1.5px] py-3.5 ${option.toneClassName}`}
          >
            {option.emoji ? `${option.emoji} ` : ""}
            {option.label}
          </button>
        ))}
      </div>
    </div>
  );
}
