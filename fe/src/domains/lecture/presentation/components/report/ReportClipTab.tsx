import { MenuIcon } from "@/shared/ui";
import { summarySections, transcript } from "../../fixtures";

/** 리포트 탭 1 (수업 클립 / 복습 클립): 강의 영상 + 수업 내용 전사 + AI 요약 문서. */
export function ReportClipTab({ title }: { title: string }) {
  return (
    <>
      <div className="mb-5 grid grid-cols-[1.35fr_1fr] items-stretch gap-5">
        {/* 강의 영상 */}
        <div className="flex flex-col overflow-hidden rounded-2xl bg-panel-video shadow-[0_8px_30px_rgba(20,25,50,.22)]">
          <div className="relative flex aspect-video items-center justify-center bg-[linear-gradient(120deg,#1c2036,#20263f_55%,#1a1f34)]">
            <div className="flex size-[66px] items-center justify-center rounded-full bg-white/15 backdrop-blur-[4px]">
              <span className="ml-[5px] text-[22px] text-white">▶</span>
            </div>
            <div className="absolute inset-x-[22px] bottom-[18px]">
              <div className="truncate text-base font-extrabold text-white [text-shadow:0_2px_8px_rgba(0,0,0,.4)]">
                {title}
              </div>
              <div className="mt-0.5 text-xs text-panel-dim">강의 다시보기</div>
            </div>
          </div>
          <div className="h-1 bg-[#2f3a37]">
            <div className="h-full w-[34%] bg-primary" />
          </div>
          <div className="flex items-center gap-4 px-4 py-3 text-[#c7ccf0]">
            <span className="text-[15px]">▶</span>
            <span className="text-[15px]">⏭</span>
            <span className="text-sm">🔊</span>
            <span className="font-mono text-[12.5px] text-panel-dim">42:30 / 2:05:30</span>
            <span className="flex-1" />
            <span className="text-[12.5px] font-bold">1.0x</span>
            <span className="text-sm">⛶</span>
          </div>
        </div>

        {/* 수업 내용 전사 */}
        <div className="relative min-h-[220px]">
          <div className="z-card absolute inset-0 flex flex-col overflow-hidden rounded-2xl">
            <div className="z-section-title shrink-0 border-b border-line-light px-[18px] py-[15px] text-[15px]">
              <MenuIcon className="text-primary" />
              수업 내용
            </div>
            <div className="min-h-0 flex-1 overflow-y-auto px-2 py-1.5">
              {transcript.map((t, i) => (
                <div key={i} className="flex cursor-pointer gap-3 rounded-[9px] px-2 py-[9px] hover:bg-faint">
                  <span className="w-[42px] shrink-0 font-mono text-xs font-bold text-primary">
                    {t.t}
                  </span>
                  <div className="text-[13px] leading-[1.55] text-ink-sub">
                    <span className="mr-1.5 font-bold text-ink-label">{t.speaker}</span>
                    {t.text}
                  </div>
                </div>
              ))}
            </div>
          </div>
        </div>
      </div>

      {/* AI 요약 문서 */}
      <div className="z-card px-7 py-6">
        <div className="z-section-title mb-4">수업 요약 레포트</div>
        <div className="flex flex-col gap-[18px]">
          {summarySections.map((s) => (
            <div key={s.h}>
              <div className="mb-1.5 text-[14.5px] font-extrabold">{s.h}</div>
              <p className="text-[13.5px] leading-[1.75] text-ink-sub">{s.p}</p>
            </div>
          ))}
        </div>
      </div>
    </>
  );
}
