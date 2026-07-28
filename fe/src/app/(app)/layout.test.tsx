import { render, screen, cleanup } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

const replace = vi.hoisted(() => vi.fn());
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace }),
  usePathname: () => "/home",
}));

const useAuth = vi.hoisted(() => vi.fn());
vi.mock("@/domains/auth", () => ({ useAuth }));

import AppLayout from "./layout";

afterEach(() => {
  cleanup();
  replace.mockClear();
});

describe("AppLayout", () => {
  it("renders nothing and does not redirect while the session is still restoring", () => {
    useAuth.mockReturnValue({
      member: null,
      isAuthenticated: false,
      isInitializing: true,
      logout: vi.fn(),
    });

    const { container } = render(
      <AppLayout>
        <div>보호된 화면</div>
      </AppLayout>,
    );

    expect(container).toBeEmptyDOMElement();
    expect(replace).not.toHaveBeenCalled();
  });

  it("redirects to /login once restore finishes without a session", () => {
    useAuth.mockReturnValue({
      member: null,
      isAuthenticated: false,
      isInitializing: false,
      logout: vi.fn(),
    });

    const { container } = render(
      <AppLayout>
        <div>보호된 화면</div>
      </AppLayout>,
    );

    expect(container).toBeEmptyDOMElement();
    expect(replace).toHaveBeenCalledWith("/login");
  });

  it("renders the app shell and children once authenticated", () => {
    useAuth.mockReturnValue({
      member: { displayName: "테스트 사용자", email: "user@example.com" },
      isAuthenticated: true,
      isInitializing: false,
      logout: vi.fn(),
    });

    render(
      <AppLayout>
        <div>보호된 화면</div>
      </AppLayout>,
    );

    expect(screen.getByText("보호된 화면")).toBeInTheDocument();
    expect(replace).not.toHaveBeenCalled();
  });
});
