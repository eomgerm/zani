"use client";

import { useState, type ReactNode } from "react";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { color, shadow } from "@/shared/lib/theme";
import { MOCK_USER } from "@/shared/lib/user";
import { Logo } from "./Logo";
import { Avatar } from "./Avatar";

type IconName = "home" | "cards" | "gear";

const NAV_ITEMS: { label: string; href: string; icon: IconName; match: string[] }[] = [
  { label: "홈", href: "/home", icon: "home", match: ["/home"] },
  { label: "내 강의실", href: "/my-lectures", icon: "cards", match: ["/my-lectures"] },
  { label: "계정 설정", href: "/settings", icon: "gear", match: ["/settings"] },
];

function NavIcon({ name, stroke }: { name: IconName; stroke: string }) {
  const common = {
    width: 18,
    height: 18,
    viewBox: "0 0 24 24",
    fill: "none",
    stroke,
    strokeWidth: 2,
    strokeLinecap: "round" as const,
    strokeLinejoin: "round" as const,
  };
  if (name === "home")
    return (
      <svg {...common}>
        <path d="M4 11l8-7 8 7" />
        <path d="M6 10v9h12v-9" />
      </svg>
    );
  if (name === "cards")
    return (
      <svg {...common}>
        <rect x="3" y="4" width="8" height="16" rx="2" />
        <rect x="13" y="4" width="8" height="9" rx="2" />
      </svg>
    );
  return (
    <svg {...common}>
      <circle cx="12" cy="12" r="3.2" />
      <path d="M12 3.5v2M12 18.5v2M3.5 12h2M18.5 12h2M6 6l1.4 1.4M16.6 16.6L18 18M18 6l-1.4 1.4M7.4 16.6L6 18" />
    </svg>
  );
}

/**
 * 홈/내 강의실/리포트/설정 화면을 감싸는 앱 셸.
 * 좌측 사이드바(내비게이션 · 프로필/로그아웃)와 본문 영역을 구성한다.
 * 로그아웃 팝오버는 시연용 로컬 상태로만 동작한다.
 */
export function AppShell({ children }: { children: ReactNode }) {
  const pathname = usePathname();
  const router = useRouter();
  const [popOpen, setPopOpen] = useState(false);

  return (
    <div style={{ display: "flex", minHeight: "100vh", background: color.surface }}>
      <aside
        style={{
          width: 246,
          flexShrink: 0,
          background: color.surface,
          borderRight: `1px solid ${color.border}`,
          display: "flex",
          flexDirection: "column",
          padding: "22px 16px",
          position: "sticky",
          top: 0,
          height: "100vh",
        }}
      >
        <div style={{ padding: "0 8px 22px" }}>
          <Logo height={44} />
        </div>

        <nav style={{ display: "flex", flexDirection: "column", gap: 4 }}>
          {NAV_ITEMS.map((item) => {
            const active = item.match.some((m) => pathname.startsWith(m));
            const c = active ? color.primary : "#8388a6";
            return (
              <Link
                key={item.href}
                href={item.href}
                style={{
                  display: "flex",
                  alignItems: "center",
                  gap: 11,
                  padding: "11px 12px",
                  borderRadius: 12,
                  width: "100%",
                  textDecoration: "none",
                  fontSize: 14.5,
                  whiteSpace: "nowrap",
                  fontWeight: active ? 800 : 600,
                  background: active ? color.primarySoft : "transparent",
                  color: c,
                }}
              >
                <NavIcon name={item.icon} stroke={c} />
                {item.label}
              </Link>
            );
          })}
        </nav>

        <div style={{ flex: 1 }} />

        <div style={{ position: "relative" }}>
          {popOpen && (
            <div
              style={{
                position: "absolute",
                bottom: 60,
                left: 0,
                right: 0,
                background: "#fff",
                border: `1px solid ${color.border}`,
                borderRadius: 14,
                padding: 6,
                boxShadow: shadow.pop,
                animation: "zPop .15s",
              }}
            >
              <button
                onClick={() => router.push("/login")}
                style={{
                  display: "flex",
                  alignItems: "center",
                  gap: 10,
                  width: "100%",
                  textAlign: "left",
                  padding: "11px 12px",
                  border: "none",
                  background: "none",
                  borderRadius: 10,
                  cursor: "pointer",
                  fontFamily: "inherit",
                  fontSize: 14,
                  fontWeight: 700,
                  color: color.red,
                }}
              >
                ↩ 로그아웃
              </button>
            </div>
          )}
          <button
            onClick={() => setPopOpen((v) => !v)}
            style={{
              display: "flex",
              alignItems: "center",
              gap: 10,
              padding: 8,
              border: `1px solid ${color.borderMint}`,
              background: "#fff",
              cursor: "pointer",
              borderRadius: 12,
              width: "100%",
              textAlign: "left",
              fontFamily: "inherit",
            }}
          >
            <Avatar initial={MOCK_USER.initial} size={36} />
            <span style={{ minWidth: 0, flex: 1 }}>
              <span
                style={{
                  display: "block",
                  fontWeight: 700,
                  fontSize: 13.5,
                  color: color.text,
                  whiteSpace: "nowrap",
                  overflow: "hidden",
                  textOverflow: "ellipsis",
                }}
              >
                {MOCK_USER.name}
              </span>
              <span
                style={{
                  display: "block",
                  fontSize: 11.5,
                  color: color.textFainter,
                  whiteSpace: "nowrap",
                  overflow: "hidden",
                  textOverflow: "ellipsis",
                }}
              >
                {MOCK_USER.email}
              </span>
            </span>
            <span style={{ color: "#b7bcd8", fontSize: 12, flexShrink: 0 }}>⋯</span>
          </button>
        </div>
      </aside>

      <main style={{ flex: 1, minWidth: 0, padding: "34px 40px", maxWidth: 1180 }}>{children}</main>
    </div>
  );
}
