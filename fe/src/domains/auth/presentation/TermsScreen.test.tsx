import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

const push = vi.hoisted(() => vi.fn());
vi.mock("next/navigation", () => ({ useRouter: () => ({ push }) }));

import { AuthProvider } from "./AuthProvider";
import { TermsScreen } from "./TermsScreen";

const noSession = () => vi.fn().mockRejectedValue(new Error("no session"));

describe("TermsScreen", () => {
  it("logs out and returns to login when leaving without agreeing", () => {
    const requestLogoutFn = vi.fn().mockResolvedValue(undefined);

    render(
      <AuthProvider requestRefreshSessionFn={noSession()} requestLogoutFn={requestLogoutFn}>
        <TermsScreen />
      </AuthProvider>,
    );

    screen.getByRole("button", { name: "돌아가기" }).click();

    expect(requestLogoutFn).toHaveBeenCalledTimes(1);
    expect(push).toHaveBeenCalledWith("/login");
  });
});
