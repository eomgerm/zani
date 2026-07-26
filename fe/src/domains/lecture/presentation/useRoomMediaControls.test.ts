import { renderHook, act } from "@testing-library/react";
import { RoomEvent } from "livekit-client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { useRoomMediaControls } from "./useRoomMediaControls";

class FakeLocalParticipant {
  isMicrophoneEnabled = true;
  isCameraEnabled = true;
  microphoneFailure: Error | null = null;
  permissions: { canPublish: boolean } | undefined;

  setMicrophoneEnabled(enabled: boolean) {
    if (this.microphoneFailure) {
      return Promise.reject(this.microphoneFailure);
    }
    this.isMicrophoneEnabled = enabled;
    return Promise.resolve();
  }

  setCameraEnabled(enabled: boolean) {
    this.isCameraEnabled = enabled;
    return Promise.resolve();
  }
}

class FakeRoom {
  localParticipant: FakeLocalParticipant | null;
  activeDevices = new Map<MediaDeviceKind, string>();
  switched: Array<{ kind: MediaDeviceKind; deviceId: string }> = [];
  switchFailure: Error | null = null;
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
    this.activeDevices.set(kind, deviceId);
    return Promise.resolve();
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

const hoisted = vi.hoisted(() => ({ room: null as FakeRoom | null }));

vi.mock("./RoomProvider", async (importOriginal) => {
  const actual = await importOriginal<typeof import("./RoomProvider")>();
  return {
    ...actual,
    useRoomConnection: () => ({
      room: hoisted.room,
      connectionState: hoisted.room ? "connected" : "connecting",
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

  it("labels a device by its ID prefix when the browser hides the label", async () => {
    stubMediaDevices([device("abcdef123456", "audioinput", "")]);
    connectedRoom();

    const { result } = renderHook(() => useRoomMediaControls());
    await act(async () => vi.advanceTimersByTime(0));

    expect(result.current.microphones).toEqual([{ value: "abcdef123456", label: "장치 abcdef" }]);
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
    expect(result.current.activeCameraId).toBeNull();
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

  it("starts with the default devices when no pre-join result is stored", async () => {
    stubMediaDevices([]);
    const room = connectedRoom();

    renderHook(() => useRoomMediaControls("ABC123"));
    await act(async () => vi.advanceTimersByTime(0));

    expect(room.switched).toEqual([]);
  });

  it("treats a revoked publish permission as the instructor restricted mode", () => {
    const room = connectedRoom();
    room.localParticipant!.permissions = { canPublish: false };

    const { result } = renderHook(() => useRoomMediaControls());
    act(() => vi.advanceTimersByTime(0));

    expect(result.current.publishBlocked).toBe(true);
  });

  it("is not restricted while the participant may publish", () => {
    const room = connectedRoom();
    room.localParticipant!.permissions = { canPublish: true };

    const { result } = renderHook(() => useRoomMediaControls());
    act(() => vi.advanceTimersByTime(0));

    expect(result.current.publishBlocked).toBe(false);
  });

  it("does not try to publish while restricted", async () => {
    const room = connectedRoom();
    room.localParticipant!.permissions = { canPublish: false };
    const { result } = renderHook(() => useRoomMediaControls());
    act(() => vi.advanceTimersByTime(0));

    await act(async () => result.current.toggleMicrophone());

    expect(room.localParticipant!.isMicrophoneEnabled).toBe(true);
    expect(result.current.mediaError).toBeNull();
  });

  it("lifts the restriction when the server grants publishing again", () => {
    const room = connectedRoom();
    room.localParticipant!.permissions = { canPublish: false };
    const { result } = renderHook(() => useRoomMediaControls());
    act(() => vi.advanceTimersByTime(0));
    expect(result.current.publishBlocked).toBe(true);

    act(() => {
      room.localParticipant!.permissions = { canPublish: true };
      room.emit(RoomEvent.ParticipantPermissionsChanged);
    });

    expect(result.current.publishBlocked).toBe(false);
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
