import Link from "next/link";
import Image from "next/image";
import { LOGO_SRC } from "@/shared/ui";

/**
 * SC-01 로그인. Google 로그인 진입 히어로 화면.
 * 프로토타입에서는 로그인 버튼이 약관 동의 화면으로 이동한다.
 */
export function LoginScreen() {
  return (
    <div className="flex min-h-screen flex-col bg-[linear-gradient(120deg,#f5f6fc_0%,#e9f8f2_45%,#edfaf5_100%)]">
      {/* 상단 내비 */}
      <div className="mx-auto flex w-full max-w-[1360px] items-center justify-between px-12 py-[26px]">
        <Image
          src={LOGO_SRC}
          alt="ZANI"
          height={52}
          width={101}
          priority
          className="h-[52px] w-auto mix-blend-multiply"
        />
      </div>

      {/* 히어로 */}
      <div className="mx-auto flex w-full max-w-[1360px] flex-1 flex-wrap items-center gap-10 px-12 pb-[60px] pt-5">
        <div className="min-w-[340px] flex-1">
          <div className="mb-3.5 text-xl font-bold tracking-[-.3px] text-ink-muted">
            수업이 깨어나는 모든 순간
          </div>
          <div className="mb-[34px] bg-[linear-gradient(120deg,#42daa0,#16b276)] bg-clip-text text-[118px] font-black leading-[.92] tracking-[-4px] text-transparent">
            ZANI
          </div>
          <div className="flex flex-wrap gap-3.5">
            <Link
              href="/terms"
              className="z-btn inline-flex items-center gap-3 rounded-[14px] border border-line-muted bg-surface px-[30px] py-4 text-base text-ink shadow-[0_10px_26px_rgba(60,70,130,.12)] hover:bg-[#f6f7ff]"
            >
              <span className="inline-block size-[22px] rounded-full bg-[conic-gradient(#ea4335,#f2bd0e,#35cf94,#4285f4)]" />
              Google 계정으로 시작하기
            </Link>
          </div>
        </div>

        {/* 일러스트 (플로팅 카드) */}
        <div className="relative h-[560px] min-w-[400px] flex-1">
          <div className="absolute inset-x-[4%] inset-y-[6%] blur-[6px] [background:radial-gradient(circle_at_30%_30%,#cbf2e3,transparent_60%),radial-gradient(circle_at_75%_75%,#ddf6ec,transparent_60%)]" />

          <FloatCard className="left-[12%] right-[16%] top-6">
            <span className="z-icon-chip">🎥</span>
            <span className="flex-1 text-base font-extrabold text-ink">실시간 화상 강의</span>
            <span className="z-pill bg-surface px-[11px] py-[5px] text-xs text-danger shadow-[0_3px_10px_rgba(224,69,95,.15)]">
              <span className="size-[7px] rounded-full bg-danger" />
              LIVE
            </span>
          </FloatCard>

          <div className="absolute left-[4%] right-[8%] top-[150px] rounded-[22px] border border-white bg-white/[.66] px-6 py-[22px] shadow-[0_20px_50px_rgba(60,70,130,.16)] backdrop-blur-lg">
            <div className="mb-2 flex items-center gap-3.5">
              <span className="z-icon-chip">📊</span>
              <span className="text-base font-extrabold text-ink">AI 학습 분석</span>
            </div>
            <div className="flex items-end gap-3">
              <svg viewBox="0 0 260 90" preserveAspectRatio="none" className="h-[88px] flex-1">
                <polyline
                  points="0,70 40,58 75,64 110,44 150,52 190,30 230,34 260,10"
                  fill="none"
                  stroke="#1cdd93"
                  strokeWidth="3"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                />
                <circle cx="260" cy="10" r="5" fill="#1cdd93" />
              </svg>
              <span className="text-[34px] font-black tracking-[-1px] text-primary">92%</span>
            </div>
          </div>

          <FloatCard className="bottom-auto left-[14%] right-[4%] top-[410px]">
            <span className="z-icon-chip">📄</span>
            <span className="text-base font-extrabold text-ink">수업 요약 &amp; 리포트</span>
          </FloatCard>
        </div>
      </div>

      <div className="border-t border-line bg-surface px-12 py-[26px]">
        <div className="mx-auto max-w-[1160px] text-right text-[12.5px] text-ink-ghost">
          © 2026 ZANI.
        </div>
      </div>
    </div>
  );
}

/** 로그인 히어로의 반투명 플로팅 카드 */
function FloatCard({ className, children }: { className: string; children: React.ReactNode }) {
  return (
    <div
      className={`absolute flex items-center gap-3.5 rounded-[22px] border border-white bg-white/[.62] px-[22px] py-5 shadow-[0_18px_46px_rgba(60,70,130,.14)] backdrop-blur-lg ${className}`}
    >
      {children}
    </div>
  );
}
