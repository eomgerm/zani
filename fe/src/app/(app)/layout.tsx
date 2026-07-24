"use client";

import type { ReactNode } from "react";
import { AppShell } from "@/shared/ui";
import { useAuth } from "@/domains/auth";

/**
 * 앱 셸 레이아웃. 홈/대시보드/내 강의실/리포트/설정 화면을 사이드바로 감싼다.
 */
export default function AppLayout({ children }: { children: ReactNode }) {
  const { member, logout } = useAuth();

  return (
    <AppShell member={member} onLogout={logout}>
      {children}
    </AppShell>
  );
}
