"use client";

import { useEffect } from "react";
import type { ReactNode } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/domains/auth";

/**
 * 사이드바 없이 화면 폭을 다 쓰는 레이아웃. 리포트처럼 그래프와 표가 넓어야 읽히는 화면이 쓴다.
 *
 * <p>로그인 가드는 앱 셸과 같다 — 셸을 벗은 화면도 로그인 없이는 열 수 없다. 돌아갈 곳은 화면
 * 스스로 둔다(리포트 헤더의 뒤로가기).
 */
export default function FullWidthLayout({ children }: { children: ReactNode }) {
  const router = useRouter();
  const { isAuthenticated, isInitializing } = useAuth();

  useEffect(() => {
    if (!isInitializing && !isAuthenticated) {
      router.replace("/login");
    }
  }, [isInitializing, isAuthenticated, router]);

  if (isInitializing || !isAuthenticated) {
    return null;
  }

  return (
    <main className="mx-auto min-h-screen w-full max-w-[1360px] px-12 py-[34px] wide:max-w-[1680px]">
      {children}
    </main>
  );
}
