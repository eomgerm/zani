import { act, renderHook, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

const auth = vi.hoisted(() => ({ accessToken: "test-access-token" as string | null }));
vi.mock("@/domains/auth", () => ({ useAuth: () => auth }));

import { ModerationRequestError } from "../infrastructure/moderationApi";
import { useModeration } from "./useModeration";

const TARGET = "p-22";

const muteParticipant = vi.fn(async () => ({ muted: true, alreadyMuted: false }));

const renderModeration = () =>
  renderHook(() => useModeration({ sessionId: "s1", muteParticipant }));

beforeEach(() => {
  auth.accessToken = "test-access-token";
  muteParticipant.mockClear();
  muteParticipant.mockResolvedValue({ muted: true, alreadyMuted: false });
});

describe("useModeration", () => {
  /** 화면은 identity 만 들고 있고 서버는 참가자 ID 를 받는다. 그 변환이 여기서 끝나야 화면이 형식을 몰라도 된다. */
  it("identity 에서 참가자 ID 를 떼어 보낸다", async () => {
    const { result } = renderModeration();

    await act(async () => {
      await result.current.mute(TARGET);
    });

    expect(muteParticipant).toHaveBeenCalledWith("s1", "22", "test-access-token");
  });

  it("형식이 아닌 identity 는 보내지 않는다", async () => {
    const { result } = renderModeration();

    await act(async () => {
      await result.current.mute("22");
    });

    expect(muteParticipant).not.toHaveBeenCalled();
  });

  it("요청 중에는 그 대상만 잠근다", async () => {
    let release!: () => void;
    muteParticipant.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          release = () => resolve({ muted: true, alreadyMuted: false });
        }),
    );
    const { result } = renderModeration();

    let pending!: Promise<void>;
    act(() => {
      pending = result.current.mute(TARGET);
    });
    await waitFor(() => expect(result.current.mutingIdentity).toBe(TARGET));

    await act(async () => {
      release();
      await pending;
    });
    expect(result.current.mutingIdentity).toBeNull();
  });

  /**
   * 실패를 삼키면 강사는 껐다고 믿는데 학생 소리는 계속 나간다. 조용해진 줄 알고 수업을 이어가므로 알아챌 방법이 없다.
   */
  it.each([
    [403, "이 수업의 강사만"],
    [404, "찾을 수 없어요"],
    [503, "미디어 서버"],
  ])("실패하면 상태에 맞는 이유를 남긴다: %s", async (status, expected) => {
    muteParticipant.mockRejectedValueOnce(new ModerationRequestError("failed", status as number));
    const { result } = renderModeration();

    await act(async () => {
      await result.current.mute(TARGET);
    });

    expect(result.current.muteError).toContain(expected as string);
  });

  it("다시 시도하면 앞선 오류를 지운다", async () => {
    muteParticipant.mockRejectedValueOnce(new ModerationRequestError("failed", 503));
    const { result } = renderModeration();
    await act(async () => {
      await result.current.mute(TARGET);
    });
    expect(result.current.muteError).not.toBeNull();

    await act(async () => {
      await result.current.mute(TARGET);
    });

    expect(result.current.muteError).toBeNull();
  });
});
