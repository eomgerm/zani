"use client";

import { useEffect } from "react";
import type { ReactNode } from "react";
import { useRouter } from "next/navigation";
import { AppShell } from "@/shared/ui";
import { useAuth } from "@/domains/auth";

/**
 * 앱 셸 레이아웃. 홈/대시보드/내 강의실/리포트/설정 화면을 사이드바로 감싼다.
 * 세션 복원이 끝나기 전에는 아무 것도 보여주지 않고, 복원 결과 로그인 상태가 아니면 로그인 화면으로 보낸다.
 */
export default function AppLayout({ children }: { children: ReactNode }) {
  const router = useRouter();
  const { member, isAuthenticated, isInitializing, logout } = useAuth();

  useEffect(() => {
    if (!isInitializing && !isAuthenticated) {
      router.replace("/login");
    }
  }, [isInitializing, isAuthenticated, router]);

  if (isInitializing || !isAuthenticated) {
    return null;
  }

  return (
    <AppShell member={member} onLogout={logout}>
      {children}
    </AppShell>
  );
}
