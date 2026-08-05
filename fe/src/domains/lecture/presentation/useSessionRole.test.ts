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
    const session = {
      sessionId: "s1",
      inviteCode: "AAA",
      title: "자료구조",
      instructorName: "박서준",
      status: "ENDED",
      role: "STUDENT",
      startedAt: "2026-08-03T09:00:00Z",
      endedAt: "2026-08-03T10:00:00Z",
      participantCount: 20,
      reportStatus: "COMPLETED",
      rejoinable: false,
    };
    const request = vi.fn().mockResolvedValue([session]);

    const { result } = renderHook(() => useSessionRole("s1", { request }));

    await waitFor(() => expect(result.current.status).toBe("ready"));
    expect(result.current.role).toBe("STUDENT");
    expect(result.current.lecture?.id).toBe("s1");
    expect(result.current.lecture?.title).toBe("자료구조");
  });

  it("reports unknown instead of guessing when the session is missing", async () => {
    const request = vi.fn().mockResolvedValue([]);

    const { result } = renderHook(() => useSessionRole("s1", { request }));

    await waitFor(() => expect(result.current.status).toBe("unknown"));
    // 강사로 가정하면 학생이 집단 경로를 불러 403 을 받는다.
    expect(result.current.role).toBeNull();
    expect(result.current.lecture).toBeNull();
  });

  it("reports unknown when the request fails", async () => {
    const request = vi.fn().mockRejectedValue(new Error("boom"));

    const { result } = renderHook(() => useSessionRole("s1", { request }));

    await waitFor(() => expect(result.current.status).toBe("unknown"));
    expect(result.current.lecture).toBeNull();
  });
});
