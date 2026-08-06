"use client";

import { ChevronLeftIcon, ChevronRightIcon } from "@/shared/ui";

const pagerBtn = "flex size-[30px] items-center justify-center rounded-full border-0";

type GridPagerProps = {
  /** 0-based 현재 페이지. */
  current: number;
  pageCount: number;
  onChange: (page: number) => void;
  /** 스크린리더가 무엇을 넘기는지 알 수 있게 한다. 화면에는 `1/3` 만 보인다. */
  label?: string;
};

/**
 * 타일 페이지 넘김. 스테이지 위에 떠 있는 방식이라 어느 배치에서도 자리를 차지하지 않는다.
 *
 * <p>가운데 아래에 띄우고 배경을 옅게 둔다 — 한쪽 끝에 붙이면 그쪽 타일만 가리고, 배치가 바뀌면
 * 하필 그 자리에 영상이 오기도 한다. 가운데는 어느 배치에서든 예측 가능한 자리다.
 */
export function GridPager({ current, pageCount, onChange, label = "페이지" }: GridPagerProps) {
  const atStart = current === 0;
  const atEnd = current === pageCount - 1;

  return (
    <div className="pointer-events-none absolute inset-x-0 bottom-4 z-[8] flex justify-center">
      <div className="pointer-events-auto flex items-center gap-2 rounded-full border border-white/10 bg-[#0e1020]/55 p-1 pl-1.5 backdrop-blur-[8px]">
        <button
          type="button"
          onClick={() => onChange(Math.max(0, current - 1))}
          disabled={atStart}
          aria-label={`이전 ${label}`}
          className={`${pagerBtn} ${
            atStart
              ? "cursor-default bg-transparent text-[#565b78]"
              : "cursor-pointer bg-white/10 text-white hover:bg-white/20"
          }`}
        >
          <ChevronLeftIcon size={16} />
        </button>
        <span className="min-w-8 text-center font-mono text-[12px] font-extrabold text-[#e7e9fb]">
          {current + 1}/{pageCount}
        </span>
        <button
          type="button"
          onClick={() => onChange(Math.min(pageCount - 1, current + 1))}
          disabled={atEnd}
          aria-label={`다음 ${label}`}
          className={`${pagerBtn} ${
            atEnd
              ? "cursor-default bg-transparent text-[#565b78]"
              : "cursor-pointer bg-white/10 text-white hover:bg-white/20"
          }`}
        >
          <ChevronRightIcon size={16} />
        </button>
      </div>
    </div>
  );
}
