import { renderHook, act } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { ScreenShareRequestError } from "../infrastructure/screenShareApi";
import { useScreenShare } from "./useScreenShare";

const hoisted = vi.hoisted(() => ({
  room: null as ReturnType<typeof buildRoom> | null,
  authState: { accessToken: "test-access-token" as string | null },
}));

vi.mock("@/domains/auth", () => ({
  useAuth: () => hoisted.authState,
}));

vi.mock("./RoomProvider", () => ({
  useRoomConnection: () => ({ room: hoisted.room }),
}));

type FakeParticipant = {
  identity: string;
  isScreenShareEnabled: boolean;
  setScreenShareEnabled: ReturnType<typeof vi.fn>;
  getTrackPublication: (source: unknown) => { videoTrack: { attach: () => void; detach: () => void } } | undefined;
};

/** LiveKit room 의 화면 공유 관련 표면만 흉내 낸다. setScreenShareEnabled 는 상태를 뒤집고 구독자에게 알린다. */
function buildRoom() {
  const handlers = new Set<() => void>();
  const emit = () => handlers.forEach((handler) => handler());
  const track = { attach: vi.fn(), detach: vi.fn() };

  const local: FakeParticipant = {
    identity: "p-1",
    isScreenShareEnabled: false,
    setScreenShareEnabled: vi.fn(async (on: boolean) => {
      local.isScreenShareEnabled = on;
      emit();
    }),
    getTrackPublication: () => (local.isScreenShareEnabled ? { videoTrack: track } : undefined),
  };
  const remotes = new Map<string, FakeParticipant>();

  return {
    localParticipant: local,
    remoteParticipants: remotes,
    on(_event: unknown, handler: () => void) {
      handlers.add(handler);
      return this;
    },
    off(_event: unknown, handler: () => void) {
      handlers.delete(handler);
      return this;
    },
    addRemoteSharer(identity: string) {
      const remote: FakeParticipant = {
        identity,
        isScreenShareEnabled: true,
        setScreenShareEnabled: vi.fn(),
        getTrackPublication: () => ({ videoTrack: { attach: vi.fn(), detach: vi.fn() } }),
      };
      remotes.set(identity, remote);
      emit();
    },
  };
}

const flush = () => act(async () => vi.advanceTimersByTimeAsync(0));

const renderScreenShare = (
  claim = vi.fn(async () => {}),
  release = vi.fn(async () => {}),
) => ({
  claim,
  release,
  ...renderHook(() => useScreenShare("session-1", { claim, release, refreshMs: 10_000 })),
});

beforeEach(() => {
  hoisted.room = buildRoom();
  hoisted.authState.accessToken = "test-access-token";
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
});

describe("useScreenShare", () => {
  it("claims the server slot before publishing the LiveKit track", async () => {
    const { claim, result } = renderScreenShare();
    await flush();

    await act(async () => {
      result.current.toggle();
      await vi.advanceTimersByTimeAsync(0);
    });

    const local = hoisted.room!.localParticipant;
    expect(claim).toHaveBeenCalledTimes(1);
    expect(local.setScreenShareEnabled).toHaveBeenCalledWith(true);
    // 슬롯을 먼저 잡은 뒤에만 트랙을 켠다 — 반대면 두 화면이 잠깐 함께 방송된다.
    expect(claim.mock.invocationCallOrder[0]).toBeLessThan(
      local.setScreenShareEnabled.mock.invocationCallOrder[0],
    );
    expect(result.current.sharing).toBe(true);
  });

  it("does not publish when the slot is already taken (409)", async () => {
    const claim = vi.fn(async () => {
      throw new ScreenShareRequestError("in use", 409);
    });
    const { result } = renderScreenShare(claim);
    await flush();

    await act(async () => {
      result.current.toggle();
      await vi.advanceTimersByTimeAsync(0);
    });

    expect(hoisted.room!.localParticipant.setScreenShareEnabled).not.toHaveBeenCalled();
    expect(result.current.sharing).toBe(false);
  });

  it("releases the slot when the screen picker is cancelled", async () => {
    const release = vi.fn(async () => {});
    const { result } = renderScreenShare(vi.fn(async () => {}), release);
    hoisted.room!.localParticipant.setScreenShareEnabled = vi.fn(async () => {
      throw new Error("user cancelled");
    });
    await flush();

    await act(async () => {
      result.current.toggle();
      await vi.advanceTimersByTimeAsync(0);
    });

    expect(release).toHaveBeenCalledTimes(1);
    expect(result.current.sharing).toBe(false);
  });

  it("blocks and does not claim while another participant is sharing", async () => {
    const { claim, result } = renderScreenShare();
    act(() => hoisted.room!.addRemoteSharer("p-2"));
    await flush();

    expect(result.current.active).toBe(true);
    expect(result.current.blocked).toBe(true);

    await act(async () => {
      result.current.toggle();
      await vi.advanceTimersByTimeAsync(0);
    });

    expect(claim).not.toHaveBeenCalled();
  });

  it("stops the track and releases the slot when toggled off", async () => {
    const release = vi.fn(async () => {});
    const { result } = renderScreenShare(vi.fn(async () => {}), release);
    await flush();
    await act(async () => {
      result.current.toggle();
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(result.current.sharing).toBe(true);

    await act(async () => {
      result.current.toggle();
      await vi.advanceTimersByTimeAsync(0);
    });

    expect(hoisted.room!.localParticipant.setScreenShareEnabled).toHaveBeenLastCalledWith(false);
    expect(release).toHaveBeenCalledTimes(1);
    expect(result.current.sharing).toBe(false);
  });

  it("refreshes the slot on an interval while sharing", async () => {
    const { claim, result } = renderScreenShare();
    await flush();
    await act(async () => {
      result.current.toggle();
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(claim).toHaveBeenCalledTimes(1);

    await act(async () => vi.advanceTimersByTimeAsync(20_000));

    // 시작 1회 + 10초 주기 갱신 2회.
    expect(claim).toHaveBeenCalledTimes(3);
  });
});
