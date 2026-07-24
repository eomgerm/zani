"use client";

import { useState, type ReactNode } from "react";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { Logo } from "./Logo";
import { Avatar } from "./Avatar";

export type AppShellMember = {
  displayName: string;
  email: string;
};

type IconName = "home" | "cards" | "gear";

const NAV_ITEMS: { label: string; href: string; icon: IconName }[] = [
  { label: "홈", href: "/home", icon: "home" },
  { label: "내 강의실", href: "/my-lectures", icon: "cards" },
  { label: "계정 설정", href: "/settings", icon: "gear" },
];

function NavIcon({ name }: { name: IconName }) {
  const common = {
    width: 18,
    height: 18,
    viewBox: "0 0 24 24",
    fill: "none",
    stroke: "currentColor",
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
 * 회원 정보와 로그아웃 동작은 호출자(app 계층)가 props로 주입한다 —
 * shared는 특정 도메인(auth)에 의존하지 않는다.
 */
export function AppShell({
  children,
  member,
  onLogout,
}: {
  children: ReactNode;
  member: AppShellMember | null;
  onLogout: () => void;
}) {
  const pathname = usePathname();
  const router = useRouter();
  const [popOpen, setPopOpen] = useState(false);

  return (
    <div className="flex min-h-screen bg-surface">
      <aside className="sticky top-0 flex h-screen w-[246px] shrink-0 flex-col border-r border-line px-4 py-[22px]">
        <div className="px-2 pb-[22px]">
          <Logo height={44} />
        </div>

        <nav className="flex flex-col gap-1">
          {NAV_ITEMS.map((item) => {
            const active = pathname.startsWith(item.href);
            return (
              <Link
                key={item.href}
                href={item.href}
                className={`flex w-full items-center gap-[11px] whitespace-nowrap rounded-xl px-3 py-[11px] text-[14.5px] no-underline ${
                  active
                    ? "bg-primary-soft font-extrabold text-primary"
                    : "font-semibold text-[#8388a6]"
                }`}
              >
                <NavIcon name={item.icon} />
                {item.label}
              </Link>
            );
          })}
        </nav>

        <div className="flex-1" />

        <div className="relative">
          {popOpen && (
            <div className="absolute bottom-[60px] left-0 right-0 animate-[zPop_.15s] rounded-[14px] border border-line bg-surface p-1.5 shadow-pop">
              <button
                onClick={() => {
                  onLogout();
                  router.push("/login");
                }}
                className="flex w-full cursor-pointer items-center gap-2.5 rounded-[10px] border-0 bg-transparent px-3 py-[11px] text-left font-sans text-sm font-bold text-danger hover:bg-[#fff0f2]"
              >
                ↩ 로그아웃
              </button>
            </div>
          )}
          <button
            onClick={() => setPopOpen((v) => !v)}
            className="flex w-full cursor-pointer items-center gap-2.5 rounded-xl border border-line-mint bg-surface p-2 text-left font-sans"
          >
            <Avatar initial={(member?.displayName ?? "?").charAt(0)} size={36} />
            <span className="min-w-0 flex-1">
              <span className="block truncate text-[13.5px] font-bold text-ink">
                {member?.displayName ?? "사용자"}
              </span>
              <span className="block truncate text-[11.5px] text-ink-fainter">
                {member?.email ?? ""}
              </span>
            </span>
            <span className="shrink-0 text-xs text-ink-quiet">⋯</span>
          </button>
        </div>
      </aside>

      <main className="min-w-0 max-w-[1180px] flex-1 px-10 py-[34px]">{children}</main>
    </div>
  );
}
