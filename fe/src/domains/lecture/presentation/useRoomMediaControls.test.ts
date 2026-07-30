import { renderHook, act } from "@testing-library/react";
import { RoomEvent } from "livekit-client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { useRoomMediaControls } from "./useRoomMediaControls";

class FakeLocalParticipant {
  isMicrophoneEnabled = true;
  isCameraEnabled = true;
  microphoneFailure: Error | null = null;
  cameraFailure: Error | null = null;
  permissions: { canPublish: boolean; canPublishSources?: number[] } | undefined;

  setMicrophoneEnabled(enabled: boolean) {
    if (this.microphoneFailure) {
      return Promise.reject(this.microphoneFailure);
    }
    this.isMicrophoneEnabled = enabled;
    return Promise.resolve();
  }

  setCameraEnabled(enabled: boolean) {
    if (this.cameraFailure) {
      return Promise.reject(this.cameraFailure);
    }
    this.isCameraEnabled = enabled;
    return Promise.resolve();
  }
}

class FakeRoom {
  localParticipant: FakeLocalParticipant | null;
  activeDevices = new Map<MediaDeviceKind, string>();
  switched: Array<{ kind: MediaDeviceKind; deviceId: string }> = [];
  switchFailure: Error | null = null;
  switchResult = true;
  private handlers = new Map<string, Set<() => void>>();

  constructor(local: FakeLocalParticipant | null) {
    this.localParticipant = local;
  }

  getActiveDevice(kind: MediaDeviceKind) {
    return this.activeDevices.get(kind);
  }

  switchActiveDevice(kind: MediaDeviceKind, deviceId: string) {
    if (this.switchFailure) {
      return Promise.reject(this.switchFailure);
    }
    this.switched.push({ kind, deviceId });
    if (!this.switchResult) {
      return Promise.resolve(false);
    }
    this.activeDevices.set(kind, deviceId);
    return Promise.resolve(true);
  }

  on(event: string, handler: () => void) {
    if (!this.handlers.has(event)) this.handlers.set(event, new Set());
    this.handlers.get(event)!.add(handler);
    return this;
  }

  off(event: string, handler: () => void) {
    this.handlers.get(event)?.delete(handler);
    return this;
  }

  emit(event: RoomEvent) {
    this.handlers.get(event)?.forEach((handler) => handler());
  }

  handlerCount() {
    let count = 0;
    this.handlers.forEach((set) => (count += set.size));
    return count;
  }
}

const hoisted = vi.hoisted(() => ({
  room: null as FakeRoom | null,
  connectionState: "connected" as "connecting" | "connected" | "error",
}));

vi.mock("./RoomProvider", async (importOriginal) => {
  const actual = await importOriginal<typeof import("./RoomProvider")>();
  return {
    ...actual,
    useRoomConnection: () => ({
      room: hoisted.room,
      connectionState: hoisted.room ? hoisted.connectionState : "connecting",
      error: null,
      retry: () => {},
    }),
  };
});

const device = (deviceId: string, kind: MediaDeviceKind, label: string) =>
  ({ deviceId, kind, label, groupId: "", toJSON: () => ({}) }) as MediaDeviceInfo;

const stubMediaDevices = (devices: MediaDeviceInfo[]) => {
  const listeners = new Set<() => void>();
  const mediaDevices = {
    enumerateDevices: vi.fn(() => Promise.resolve(devices)),
    addEventListener: (_: string, handler: () => void) => listeners.add(handler),
    removeEventListener: (_: string, handler: () => void) => listeners.delete(handler),
  };
  vi.stubGlobal("navigator", { ...navigator, mediaDevices });
  return { mediaDevices, fireDeviceChange: () => listeners.forEach((handler) => handler()) };
};

const connectedRoom = () => {
  const room = new FakeRoom(new FakeLocalParticipant());
  hoisted.room = room;
  return room;
};

beforeEach(() => {
  hoisted.room = null;
  hoisted.connectionState = "connected";
  sessionStorage.clear();
  vi.useFakeTimers();
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

describe("useRoomMediaControls", () => {
  it("mirrors the local participant publish state once connected", () => {
    const room = connectedRoom();
    room.localParticipant!.isCameraEnabled = false;

    const { result } = renderHook(() => useRoomMediaControls());
    act(() => vi.advanceTimersByTime(0));

    expect(result.current.ready).toBe(true);
    expect(result.current.microphoneEnabled).toBe(true);
    expect(result.current.cameraEnabled).toBe(false);
  });

  it("is not ready while the room is still connecting", () => {
    const { result } = renderHook(() => useRoomMediaControls());
    act(() => vi.advanceTimersByTime(0));

    expect(result.current.ready).toBe(false);
    expect(result.current.microphoneEnabled).toBe(false);
  });

  it("stops publishing the microphone when toggled off", async () => {
    const room = connectedRoom();
    const { result } = renderHook(() => useRoomMediaControls());
    act(() => vi.advanceTimersByTime(0));

    await act(async () => result.current.toggleMicrophone());

    expect(room.localParticipant!.isMicrophoneEnabled).toBe(false);
    expect(result.current.microphoneEnabled).toBe(false);
  });

  it("publishes the camera again when toggled back on", async () => {
    const room = connectedRoom();
    room.localParticipant!.isCameraEnabled = false;
    const { result } = renderHook(() => useRoomMediaControls());
    act(() => vi.advanceTimersByTime(0));

    await act(async () => result.current.toggleCamera());

    expect(room.localParticipant!.isCameraEnabled).toBe(true);
    expect(result.current.cameraEnabled).toBe(true);
  });

  it("reports an error when the device cannot be published", async () => {
    const room = connectedRoom();
    room.localParticipant!.microphoneFailure = new Error("device busy");
    const { result } = renderHook(() => useRoomMediaControls());
    act(() => vi.advanceTimersByTime(0));

    await act(async () => result.current.toggleMicrophone());

    expect(result.current.mediaError).not.toBeNull();
    expect(result.current.microphoneEnabled).toBe(true);
  });

  it("reports a denied camera apart from a device that merely failed", async () => {
    const room = connectedRoom();
    // 브라우저 권한 거부는 NotAllowedError 로 온다(LiveKit MediaDeviceFailure 분류 기준).
    const denied = new Error("denied");
    denied.name = "NotAllowedError";
    room.localParticipant!.cameraFailure = denied;
    const { result } = renderHook(() => useRoomMediaControls());
    act(() => vi.advanceTimersByTime(0));

    await act(async () => result.current.toggleCamera());

    expect(result.current.cameraPermissionDenied).toBe(true);
    expect(result.current.mediaError).toContain("권한");
  });

  it("does not call a camera that is in use denied", async () => {
    const room = connectedRoom();
    // 장치 점유 실패는 NotReadableError 로 온다. 권한 거부와 구분돼야 한다.
    const busy = new Error("device busy");
    busy.name = "NotReadableError";
    room.localParticipant!.cameraFailure = busy;
    const { result } = renderHook(() => useRoomMediaControls());
    act(() => vi.advanceTimersByTime(0));

    await act(async () => result.current.toggleCamera());

    expect(result.current.mediaError).not.toBeNull();
    expect(result.current.cameraPermissionDenied).toBe(false);
  });

  it("stops reporting a denied camera once it can be published again", async () => {
    const room = connectedRoom();
    const denied = new Error("denied");
    denied.name = "NotAllowedError";
    room.localParticipant!.cameraFailure = denied;
    const { result } = renderHook(() => useRoomMediaControls());
    act(() => vi.advanceTimersByTime(0));
    await act(async () => result.current.toggleCamera());

    room.localParticipant!.cameraFailure = null;
    await act(async () => result.current.toggleCamera());

    expect(result.current.cameraPermissionDenied).toBe(false);
    expect(result.current.mediaError).toBeNull();
  });

  it("keeps a denied camera denied after switching to another camera", async () => {
    const room = connectedRoom();
    const denied = new Error("denied");
    denied.name = "NotAllowedError";
    room.localParticipant!.cameraFailure = denied;
    const { result } = renderHook(() => useRoomMediaControls());
    act(() => vi.advanceTimersByTime(0));
    await act(async () => result.current.toggleCamera());

    // 장치 전환은 publish 를 시도하지 않으므로 권한이 허용됐다는 증거가 아니다.
    await act(async () => result.current.selectCamera("cam-2"));

    expect(result.current.cameraPermissionDenied).toBe(true);
  });

  it("does not report a denied camera when only the microphone was denied", async () => {
    const room = connectedRoom();
    const denied = new Error("denied");
    denied.name = "NotAllowedError";
    room.localParticipant!.microphoneFailure = denied;
    const { result } = renderHook(() => useRoomMediaControls());
    act(() => vi.advanceTimersByTime(0));

    await act(async () => result.current.toggleMicrophone());

    expect(result.current.cameraPermissionDenied).toBe(false);
  });

  it("clears the error after the next successful toggle", async () => {
    const room = connectedRoom();
    room.localParticipant!.microphoneFailure = new Error("device busy");
    const { result } = renderHook(() => useRoomMediaControls());
    act(() => vi.advanceTimersByTime(0));
    await act(async () => result.current.toggleMicrophone());

    room.localParticipant!.microphoneFailure = null;
    await act(async () => result.current.toggleMicrophone());

    expect(result.current.mediaError).toBeNull();
    expect(result.current.microphoneEnabled).toBe(false);
  });

  it("keeps the microphone error while an unrelated camera toggle succeeds", async () => {
    const room = connectedRoom();
    room.localParticipant!.microphoneFailure = new Error("device busy");
    const { result } = renderHook(() => useRoomMediaControls());
    act(() => vi.advanceTimersByTime(0));
    await act(async () => result.current.toggleMicrophone());
    const microphoneError = result.current.mediaError;
    expect(microphoneError).not.toBeNull();

    await act(async () => result.current.toggleCamera());

    expect(result.current.mediaError).toBe(microphoneError);
  });

  it("keeps a camera switch error until the camera itself works again", async () => {
    stubMediaDevices([]);
    const room = connectedRoom();
    room.switchResult = false;
    const { result } = renderHook(() => useRoomMediaControls());
    await act(async () => vi.advanceTimersByTime(0));
    await act(async () => result.current.selectCamera("cam-2"));
    expect(result.current.mediaError).not.toBeNull();

    room.switchResult = true;
    await act(async () => result.current.selectMicrophone("mic-2"));
    expect(result.current.mediaError).not.toBeNull();

    await act(async () => result.current.selectCamera("cam-3"));

    expect(result.current.mediaError).toBeNull();
  });

  it("re-reads the publish state when a track is muted elsewhere", () => {
    const room = connectedRoom();
    const { result } = renderHook(() => useRoomMediaControls());
    act(() => vi.advanceTimersByTime(0));

    act(() => {
      room.localParticipant!.isMicrophoneEnabled = false;
      room.emit(RoomEvent.TrackMuted);
    });

    expect(result.current.microphoneEnabled).toBe(false);
  });

  it("ignores toggles while no local participant exists", async () => {
    hoisted.room = new FakeRoom(null);
    const { result } = renderHook(() => useRoomMediaControls());
    act(() => vi.advanceTimersByTime(0));

    await act(async () => result.current.toggleCamera());

    expect(result.current.mediaError).toBeNull();
    expect(result.current.ready).toBe(false);
  });

  it("lists the available microphones and cameras", async () => {
    stubMediaDevices([
      device("mic-1", "audioinput", "내장 마이크"),
      device("cam-1", "videoinput", "내장 카메라"),
      device("out-1", "audiooutput", "스피커"),
    ]);
    connectedRoom();

    const { result } = renderHook(() => useRoomMediaControls());
    await act(async () => vi.advanceTimersByTime(0));

    expect(result.current.microphones).toEqual([{ value: "mic-1", label: "내장 마이크" }]);
    expect(result.current.cameras).toEqual([{ value: "cam-1", label: "내장 카메라" }]);
  });

  it("labels a device the same way as the pre-join check when the browser hides it", async () => {
    stubMediaDevices([device("abcdef123456", "audioinput", "")]);
    connectedRoom();

    const { result } = renderHook(() => useRoomMediaControls());
    await act(async () => vi.advanceTimersByTime(0));

    expect(result.current.microphones).toEqual([{ value: "abcdef123456", label: "마이크 1" }]);
  });

  it("refreshes the device list when the browser reports a device change", async () => {
    const { mediaDevices, fireDeviceChange } = stubMediaDevices([
      device("mic-1", "audioinput", "내장 마이크"),
    ]);
    connectedRoom();
    renderHook(() => useRoomMediaControls());
    await act(async () => vi.advanceTimersByTime(0));
    expect(mediaDevices.enumerateDevices).toHaveBeenCalledTimes(1);

    await act(async () => fireDeviceChange());

    expect(mediaDevices.enumerateDevices).toHaveBeenCalledTimes(2);
  });

  it("switches the active microphone and exposes the new device", async () => {
    stubMediaDevices([]);
    const room = connectedRoom();
    const { result } = renderHook(() => useRoomMediaControls());
    await act(async () => vi.advanceTimersByTime(0));

    await act(async () => result.current.selectMicrophone("mic-2"));

    expect(room.switched).toEqual([{ kind: "audioinput", deviceId: "mic-2" }]);
    expect(result.current.activeMicrophoneId).toBe("mic-2");
  });

  it("reports an error when the selected device cannot be used", async () => {
    stubMediaDevices([]);
    const room = connectedRoom();
    room.switchFailure = new Error("device in use");
    const { result } = renderHook(() => useRoomMediaControls());
    await act(async () => vi.advanceTimersByTime(0));

    await act(async () => result.current.selectCamera("cam-2"));

    expect(result.current.mediaError).not.toBeNull();
  });

  it("keeps the devices chosen during the pre-join check", async () => {
    stubMediaDevices([]);
    sessionStorage.setItem(
      "zani:prejoin:ABC123",
      JSON.stringify({
        cameraDeviceId: "cam-9",
        microphoneDeviceId: "mic-9",
        testedAt: "2026-07-26T12:00:00.000Z",
      }),
    );
    const room = connectedRoom();

    renderHook(() => useRoomMediaControls("ABC123"));
    await act(async () => vi.advanceTimersByTime(0));

    expect(room.switched).toEqual([
      { kind: "audioinput", deviceId: "mic-9" },
      { kind: "videoinput", deviceId: "cam-9" },
    ]);
  });

  /** 장치 점검을 통과하고 들어왔는데 둘 다 꺼져 있으면 고장으로 읽힌다. */
  it("입장 전 점검을 거쳐 들어오면 카메라·마이크를 켠 상태로 시작한다", async () => {
    stubMediaDevices([]);
    sessionStorage.setItem(
      "zani:prejoin:ABC123",
      JSON.stringify({ cameraDeviceId: null, microphoneDeviceId: null, testedAt: "2026-07-26T12:00:00.000Z" }),
    );
    const room = connectedRoom();
    room.localParticipant!.isCameraEnabled = false;
    room.localParticipant!.isMicrophoneEnabled = false;

    renderHook(() => useRoomMediaControls("ABC123"));
    await act(async () => vi.advanceTimersByTime(0));

    expect(room.localParticipant!.isCameraEnabled).toBe(true);
    expect(room.localParticipant!.isMicrophoneEnabled).toBe(true);
  });

  /** 서버가 publish 를 허락하지 않은 소스를 켜려 들면 LiveKit 이 거절한다. 켜려는 시도 자체를 하지 않는다. */
  it("서버가 막은 소스는 켜지 않는다", async () => {
    stubMediaDevices([]);
    sessionStorage.setItem(
      "zani:prejoin:ABC123",
      JSON.stringify({ cameraDeviceId: null, microphoneDeviceId: null, testedAt: "2026-07-26T12:00:00.000Z" }),
    );
    const room = connectedRoom();
    room.localParticipant!.isCameraEnabled = false;
    room.localParticipant!.isMicrophoneEnabled = false;
    // 마이크만 허용한다.
    room.localParticipant!.permissions = { canPublish: true, canPublishSources: [2] };

    renderHook(() => useRoomMediaControls("ABC123"));
    await act(async () => vi.advanceTimersByTime(0));

    expect(room.localParticipant!.isMicrophoneEnabled).toBe(true);
    expect(room.localParticipant!.isCameraEnabled).toBe(false);
  });

  it("starts with the default devices when no pre-join result is stored", async () => {
    stubMediaDevices([]);
    const room = connectedRoom();

    renderHook(() => useRoomMediaControls("ABC123"));
    await act(async () => vi.advanceTimersByTime(0));

    expect(room.switched).toEqual([]);
  });

  it("blocks only the source the server left out of the publish grant", () => {
    // 서버는 canPublish 를 항상 true 로 두고 canPublishSources 로 제한을 표현한다(가이드 §8).
    const room = connectedRoom();
    room.localParticipant!.permissions = { canPublish: true, canPublishSources: [1] };

    const { result } = renderHook(() => useRoomMediaControls());
    act(() => vi.advanceTimersByTime(0));

    expect(result.current.microphoneBlocked).toBe(true);
    expect(result.current.cameraBlocked).toBe(false);
  });

  it("blocks both sources when publishing is revoked entirely", () => {
    const room = connectedRoom();
    room.localParticipant!.permissions = { canPublish: false };

    const { result } = renderHook(() => useRoomMediaControls());
    act(() => vi.advanceTimersByTime(0));

    expect(result.current.microphoneBlocked).toBe(true);
    expect(result.current.cameraBlocked).toBe(true);
  });

  it("treats an empty source grant as unrestricted", () => {
    const room = connectedRoom();
    room.localParticipant!.permissions = { canPublish: true, canPublishSources: [] };

    const { result } = renderHook(() => useRoomMediaControls());
    act(() => vi.advanceTimersByTime(0));

    expect(result.current.microphoneBlocked).toBe(false);
    expect(result.current.cameraBlocked).toBe(false);
  });

  it("does not try to publish a blocked source", async () => {
    const room = connectedRoom();
    room.localParticipant!.permissions = { canPublish: true, canPublishSources: [1] };
    const { result } = renderHook(() => useRoomMediaControls());
    act(() => vi.advanceTimersByTime(0));

    await act(async () => result.current.toggleMicrophone());

    expect(room.localParticipant!.isMicrophoneEnabled).toBe(true);
    expect(result.current.mediaError).toBeNull();
  });

  it("lifts the restriction when the server grants the source again", () => {
    const room = connectedRoom();
    room.localParticipant!.permissions = { canPublish: true, canPublishSources: [1] };
    const { result } = renderHook(() => useRoomMediaControls());
    act(() => vi.advanceTimersByTime(0));
    expect(result.current.microphoneBlocked).toBe(true);

    act(() => {
      room.localParticipant!.permissions = { canPublish: true, canPublishSources: [1, 2] };
      room.emit(RoomEvent.ParticipantPermissionsChanged);
    });

    expect(result.current.microphoneBlocked).toBe(false);
  });

  it("is not ready while LiveKit is reconnecting", () => {
    connectedRoom();
    hoisted.connectionState = "connecting";

    const { result } = renderHook(() => useRoomMediaControls());
    act(() => vi.advanceTimersByTime(0));

    expect(result.current.ready).toBe(false);
  });

  it("reports an error when the device change silently falls back", async () => {
    stubMediaDevices([]);
    const room = connectedRoom();
    room.switchResult = false;
    const { result } = renderHook(() => useRoomMediaControls());
    await act(async () => vi.advanceTimersByTime(0));

    await act(async () => result.current.selectMicrophone("mic-2"));

    expect(result.current.mediaError).not.toBeNull();
  });

  it("shows the requested device until LiveKit reports the active one", async () => {
    stubMediaDevices([]);
    const room = connectedRoom();
    room.switchResult = false;
    const { result } = renderHook(() => useRoomMediaControls());
    await act(async () => vi.advanceTimersByTime(0));

    await act(async () => result.current.selectCamera("cam-7"));

    expect(result.current.activeCameraId).toBe("cam-7");
  });

  it("keeps the last device list when enumeration fails", async () => {
    const { mediaDevices, fireDeviceChange } = stubMediaDevices([
      device("mic-1", "audioinput", "내장 마이크"),
    ]);
    connectedRoom();
    const { result } = renderHook(() => useRoomMediaControls());
    await act(async () => vi.advanceTimersByTime(0));

    mediaDevices.enumerateDevices.mockRejectedValueOnce(new Error("blocked"));
    await act(async () => fireDeviceChange());

    expect(result.current.microphones).toEqual([{ value: "mic-1", label: "내장 마이크" }]);
  });

  it("ignores a second toggle while the first publish is still running", async () => {
    const room = connectedRoom();
    let release = () => {};
    room.localParticipant!.setMicrophoneEnabled = (enabled: boolean) =>
      new Promise<void>((resolve) => {
        release = () => {
          room.localParticipant!.isMicrophoneEnabled = enabled;
          resolve();
        };
      });
    const { result } = renderHook(() => useRoomMediaControls());
    act(() => vi.advanceTimersByTime(0));

    act(() => {
      result.current.toggleMicrophone();
      result.current.toggleMicrophone();
    });
    await act(async () => release());

    expect(result.current.microphoneEnabled).toBe(false);
  });

  it("drops a publish result that arrives after the room was replaced", async () => {
    const room = connectedRoom();
    let release = () => {};
    room.localParticipant!.setMicrophoneEnabled = () =>
      new Promise<void>((resolve) => {
        release = resolve;
      });
    const { result, rerender } = renderHook(() => useRoomMediaControls());
    act(() => vi.advanceTimersByTime(0));
    act(() => result.current.toggleMicrophone());

    hoisted.room = null;
    rerender();
    act(() => vi.advanceTimersByTime(0));
    await act(async () => release());

    expect(result.current.ready).toBe(false);
    expect(result.current.microphoneEnabled).toBe(false);
  });

  it("removes the devicechange listener on unmount", async () => {
    const { fireDeviceChange, mediaDevices } = stubMediaDevices([]);
    connectedRoom();
    const { unmount } = renderHook(() => useRoomMediaControls());
    await act(async () => vi.advanceTimersByTime(0));

    unmount();
    await act(async () => fireDeviceChange());

    expect(mediaDevices.enumerateDevices).toHaveBeenCalledTimes(1);
  });

  it("removes every room listener on unmount", () => {
    const room = connectedRoom();
    const { unmount } = renderHook(() => useRoomMediaControls());
    act(() => vi.advanceTimersByTime(0));
    expect(room.handlerCount()).toBeGreaterThan(0);

    unmount();

    expect(room.handlerCount()).toBe(0);
  });
});
