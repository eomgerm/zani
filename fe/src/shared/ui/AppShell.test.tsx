import { render, screen, cleanup, fireEvent } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

const push = vi.hoisted(() => vi.fn());
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push }),
  usePathname: () => "/home",
}));

import { AppShell } from "./AppShell";

const MEMBER = { displayName: "테스트 사용자", email: "user@example.com" };

function renderShell() {
  return render(
    <AppShell member={MEMBER} onLogout={vi.fn()}>
      <div>본문</div>
    </AppShell>,
  );
}

function profileButton() {
  return screen.getByRole("button", { name: /테스트 사용자/ });
}

function logoutButton() {
  return screen.queryByRole("button", { name: /로그아웃/ });
}

afterEach(() => {
  cleanup();
  push.mockClear();
});

describe("AppShell 프로필 팝오버", () => {
  it("회원 카드를 누르면 로그아웃이 열린다", () => {
    renderShell();

    expect(logoutButton()).not.toBeInTheDocument();
    fireEvent.click(profileButton());
    expect(logoutButton()).toBeInTheDocument();
  });

  it("바깥을 누르면 닫힌다", () => {
    renderShell();
    fireEvent.click(profileButton());

    fireEvent.pointerDown(screen.getByText("본문"));

    expect(logoutButton()).not.toBeInTheDocument();
  });

  it("팝오버 안을 눌러도 닫히지 않는다", () => {
    renderShell();
    fireEvent.click(profileButton());

    fireEvent.pointerDown(logoutButton()!);

    expect(logoutButton()).toBeInTheDocument();
  });

  it("회원 카드를 다시 누르면 닫힌다 — 바깥 클릭 처리가 토글을 잡아먹지 않는다", () => {
    renderShell();
    fireEvent.click(profileButton());

    fireEvent.pointerDown(profileButton());
    fireEvent.click(profileButton());

    expect(logoutButton()).not.toBeInTheDocument();
  });
});
