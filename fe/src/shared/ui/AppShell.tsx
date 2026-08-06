"use client";

import { useCallback, useRef, useState, type ReactNode } from "react";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { Logo } from "./Logo";
import { Avatar } from "./Avatar";
import { PictoCards, PictoGear, PictoHome } from "./pictograms";
import { useDismissOnOutsidePointer } from "./useDismissOnOutsidePointer";

export type AppShellMember = {
  displayName: string;
  email: string;
};

const NAV_ITEMS: { label: string; href: string; Icon: typeof PictoHome }[] = [
  { label: "홈", href: "/home", Icon: PictoHome },
  { label: "내 강의실", href: "/my-lectures", Icon: PictoCards },
  { label: "계정 설정", href: "/settings", Icon: PictoGear },
];

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
  const profileRef = useRef<HTMLDivElement | null>(null);

  useDismissOnOutsidePointer(
    profileRef,
    popOpen,
    useCallback(() => setPopOpen(false), []),
  );

  return (
    <div className="flex min-h-screen bg-surface">
      <aside className="sticky top-0 flex h-screen w-[246px] shrink-0 flex-col border-r border-line px-4 py-[22px]">
        {/* 시안은 여백 있는 원본을 62px 로 얹는다 — 여백을 잘라 둔 우리 파일에서는 24px 이 같은 크기다. */}
        <div className="px-2 pb-[22px]">
          <Logo height={24} />
        </div>

        <nav className="flex flex-col gap-1">
          {NAV_ITEMS.map(({ label, href, Icon }) => {
            const active = pathname.startsWith(href);
            return (
              <Link
                key={href}
                href={href}
                className={`flex w-full items-center gap-[13px] whitespace-nowrap rounded-[13px] px-3.5 py-3 text-[14.5px] no-underline ${
                  active ? "bg-primary-soft font-extrabold text-primary" : "font-bold text-ink-sub"
                }`}
              >
                {/* 비활성 상태에서는 아이콘만 라벨보다 옅게 둔다 — 색은 글자 색을 따른다 */}
                <span className={`flex shrink-0 ${active ? "" : "text-[#8388a6]"}`}>
                  <Icon size={21} tone="currentColor" />
                </span>
                {label}
              </Link>
            );
          })}
        </nav>

        <div className="flex-1" />

        {/* 팝오버와 여는 버튼을 한 ref 안에 둔다 — 버튼을 다시 눌러 닫는 토글이 살아 있어야 한다. */}
        <div ref={profileRef} className="relative">
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
            className="flex w-full cursor-pointer items-center gap-2.5 rounded-xl border border-line-mint bg-surface p-2 text-left font-sans hover:bg-[#f6f7fd]"
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

      {/* mx-auto 가 없으면 와이드 모니터에서 본문이 사이드바에 붙고 오른쪽이 통째로 빈다.
          (full) 레이아웃과 같은 규칙으로 가운데에 두고, 넓어지면 상한만 올린다. */}
      <main className="mx-auto min-w-0 max-w-[1180px] flex-1 px-10 py-[34px] wide:max-w-[1440px]">
        {children}
      </main>
    </div>
  );
}
