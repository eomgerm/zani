import { renderHook, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

const auth = vi.hoisted(() => ({ accessToken: "test-access-token" as string | null }));
vi.mock("@/domains/auth", () => ({ useAuth: () => auth }));

import { useSessionRole } from "./useSessionRole";

beforeEach(() => {
  auth.accessToken = "test-access-token";
});

describe("useSessionRole", () => {
  it("returns the role for the session", async () => {
    const request = vi.fn().mockResolvedValue([
      { sessionId: "s1", inviteCode: "AAA", status: "ENDED", role: "STUDENT" },
    ]);

    const { result } = renderHook(() => useSessionRole("s1", { request }));

    await waitFor(() => expect(result.current.status).toBe("ready"));
    expect(result.current.role).toBe("STUDENT");
  });

  it("reports unknown instead of guessing when the session is missing", async () => {
    const request = vi.fn().mockResolvedValue([]);

    const { result } = renderHook(() => useSessionRole("s1", { request }));

    await waitFor(() => expect(result.current.status).toBe("unknown"));
    // 강사로 가정하면 학생이 집단 경로를 불러 403 을 받는다.
    expect(result.current.role).toBeNull();
  });

  it("reports unknown when the request fails", async () => {
    const request = vi.fn().mockRejectedValue(new Error("boom"));

    const { result } = renderHook(() => useSessionRole("s1", { request }));

    await waitFor(() => expect(result.current.status).toBe("unknown"));
  });
});
