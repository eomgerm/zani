"use client";

import { useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import Image from "next/image";
import { LOGO_SRC } from "@/shared/ui";
import { useAuth } from "./AuthProvider";
import {
  loadGoogleIdentityScript,
  initializeGoogleSignIn,
  renderGoogleSignInButton,
} from "../infrastructure/googleIdentityScript";

const GOOGLE_CLIENT_ID = process.env.NEXT_PUBLIC_GOOGLE_CLIENT_ID ?? "";

/**
 * SC-01 로그인. Google 로그인 진입 히어로 화면.
 * Google Identity Services 버튼으로 로그인하면 신규 회원은 약관 동의 화면으로,
 * 기존 회원은 홈으로 이동한다.
 */
export function LoginScreen() {
  const router = useRouter();
  const { loginWithGoogle } = useAuth();
  const buttonContainerRef = useRef<HTMLDivElement>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;

    loadGoogleIdentityScript()
      .then(() => {
        if (cancelled || !buttonContainerRef.current) return;

        initializeGoogleSignIn(GOOGLE_CLIENT_ID, async (idToken) => {
          try {
            const { newMember } = await loginWithGoogle(idToken);
            router.push(newMember ? "/terms" : "/home");
          } catch (loginError) {
            console.error("Google login failed:", loginError);
            setError("로그인에 실패했습니다. 다시 시도해주세요.");
          }
        });
        renderGoogleSignInButton(buttonContainerRef.current);
      })
      .catch((loadError) => {
        console.error("Google Identity Services failed to load:", loadError);
        setError("Google 로그인을 준비하지 못했습니다.");
      });

    return () => {
      cancelled = true;
    };
  }, [loginWithGoogle, router]);

  return (
    <div className="flex min-h-screen flex-col bg-[linear-gradient(120deg,#f5f6fc_0%,#e9f8f2_45%,#edfaf5_100%)]">
      {/* 상단 내비 — 히어로가 워드마크를 크게 보여주므로 로고를 겹쳐 놓지 않는다(시안). */}
      <div className="mx-auto w-full max-w-[1360px] px-12 py-[26px]" />

      {/* 히어로 */}
      <div className="mx-auto flex w-full max-w-[1360px] flex-1 flex-wrap items-center gap-10 px-12 pb-[60px] pt-5">
        <div className="min-w-[340px] flex-1">
          <div className="text-[22px] font-bold tracking-[-.3px] text-ink-muted">
            수업이 깨어나는 모든 순간
          </div>
          {/*
            히어로 워드마크. 시안은 여백이 있는 원본을 height 200px 로 얹고 음수 마진으로 여백을
            상쇄했다 — 우리 파일은 여백을 잘라 뒀으므로 같은 크기가 78px 이고 마진 보정도 필요 없다.
          */}
          <Image
            src={LOGO_SRC}
            alt="ZANI"
            height={78}
            width={210}
            priority
            className="mb-[18px] mt-2 block h-[78px] w-auto"
          />
          <div className="flex flex-wrap items-center gap-3.5">
            <div ref={buttonContainerRef} />
            {error && <p className="text-sm text-danger">{error}</p>}
          </div>
        </div>

        {/* 일러스트 (플로팅 카드) */}
        <div className="relative h-[560px] min-w-[400px] flex-1">
          <div className="absolute inset-x-[4%] inset-y-[6%] blur-[6px] [background:radial-gradient(circle_at_30%_30%,#cbf2e3,transparent_60%),radial-gradient(circle_at_75%_75%,#ddf6ec,transparent_60%)]" />

          <FloatCard className="left-[12%] right-[16%] top-6">
            <span className="z-icon-chip">
              <HeroIcon>
                <rect x="2" y="6" width="13" height="12" rx="2.5" />
                <path d="M22 8l-5 4 5 4z" />
              </HeroIcon>
            </span>
            <span className="flex-1 whitespace-nowrap text-base font-extrabold text-ink">
              실시간 화상 강의
            </span>
            <span className="z-pill bg-surface px-[11px] py-[5px] text-xs text-danger shadow-[0_3px_10px_rgba(224,69,95,.15)]">
              <span className="size-[7px] rounded-full bg-danger" />
              LIVE
            </span>
          </FloatCard>

          <div className="absolute left-[4%] right-[8%] top-[150px] rounded-[22px] border border-white bg-white/[.66] px-6 py-[22px] shadow-[0_20px_50px_rgba(60,70,130,.16)] backdrop-blur-lg">
            <div className="mb-2 flex items-center gap-3.5">
              <span className="z-icon-chip">
                <HeroIcon strokeWidth={2.4}>
                  <path d="M5 20V13" />
                  <path d="M12 20V6" />
                  <path d="M19 20v-9" />
                </HeroIcon>
              </span>
              <span className="text-base font-extrabold text-ink">AI 학습 분석</span>
            </div>
            <div className="flex items-end gap-3">
              <svg viewBox="0 0 260 90" preserveAspectRatio="none" className="h-[88px] flex-1">
                <polyline
                  points="0,70 40,58 75,64 110,44 150,52 190,30 230,34 260,10"
                  fill="none"
                  stroke="#10b981"
                  strokeWidth="3"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                />
                <circle cx="260" cy="10" r="5" fill="#10b981" />
              </svg>
              <span className="text-[34px] font-black tracking-[-1px] text-primary">92%</span>
            </div>
          </div>

          <FloatCard className="bottom-auto left-[14%] right-[4%] top-[368px]">
            <span className="z-icon-chip">
              <HeroIcon className="text-[#15bd7d]" strokeWidth={2.2}>
                <path d="M14 3v5h5" />
                <path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z" />
              </HeroIcon>
            </span>
            <span className="text-base font-extrabold text-ink">수업 요약 &amp; 리포트</span>
          </FloatCard>

          {/* 일러스트 주변을 떠다니는 원형 아이콘 */}
          <FloatBubble className="right-[2%] top-[120px] size-14 bg-[linear-gradient(135deg,#42daa0,#48c69b)] shadow-[0_12px_28px_rgba(18,184,134,.35)]">
            <svg width="26" height="26" viewBox="0 0 24 24" fill="#fff" aria-hidden="true">
              <circle cx="9" cy="8" r="3.2" />
              <circle cx="16.5" cy="9" r="2.6" />
              <path d="M3 19c0-3 3-4.5 6-4.5s6 1.5 6 4.5z" />
              <path d="M15 19c0-2.2 1.6-3.4 3.6-3.4S22 16.8 22 19z" />
            </svg>
          </FloatBubble>
          <FloatBubble className="bottom-[120px] left-[2%] size-[52px] bg-[linear-gradient(135deg,#57cfa1,#73ddb4)] shadow-[0_12px_26px_rgba(90,169,230,.35)]">
            <svg width="24" height="24" viewBox="0 0 24 24" fill="#fff" aria-hidden="true">
              <path d="M12 2l8 3v6c0 5-3.5 8.5-8 11-4.5-2.5-8-6-8-11V5z" />
            </svg>
          </FloatBubble>
          <FloatBubble className="bottom-11 right-[6%] size-14 bg-[linear-gradient(135deg,#34dc9b,#53dca7)] shadow-[0_12px_28px_rgba(34,196,147,.35)]">
            <HeroIcon size={26} className="text-white" strokeWidth={2.2}>
              <path d="M14 3v5h5" />
              <path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z" />
            </HeroIcon>
          </FloatBubble>
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

/** 히어로 카드/버블 안에 들어가는 스트로크 아이콘. 기본색은 브랜드 그린. */
function HeroIcon({
  children,
  size = 22,
  strokeWidth = 2.2,
  className = "text-primary",
}: {
  children: React.ReactNode;
  size?: number;
  strokeWidth?: number;
  className?: string;
}) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={strokeWidth}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      className={className}
    >
      {children}
    </svg>
  );
}

/** 일러스트 주변을 떠다니는 원형 아이콘 버블 */
function FloatBubble({ className, children }: { className: string; children: React.ReactNode }) {
  return (
    <span className={`absolute flex items-center justify-center rounded-full ${className}`}>
      {children}
    </span>
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
