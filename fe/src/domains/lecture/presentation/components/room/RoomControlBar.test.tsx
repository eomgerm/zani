import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { RoomControlBar } from "./RoomControlBar";

const renderBar = (overrides: Partial<Parameters<typeof RoomControlBar>[0]> = {}) => {
  const props = {
    isInstructor: true,
    me: { mic: true, cam: true, hand: false },
    mediaDisabled: false,
    microphoneBlocked: false,
    cameraBlocked: false,
    microphones: [
      { value: "mic-1", label: "내장 마이크" },
      { value: "mic-2", label: "이어폰 마이크" },
    ],
    cameras: [{ value: "cam-1", label: "내장 카메라" }],
    activeMicrophoneId: "mic-1",
    activeCameraId: "cam-1",
    onSelectMicrophone: vi.fn(),
    onSelectCamera: vi.fn(),
    sharing: false,
    shareBlocked: false,
    reactMenuOpen: false,
    interactionDisabled: false,
    onToggleMic: vi.fn(),
    onToggleCam: vi.fn(),
    onToggleShare: vi.fn(),
    onToggleHand: vi.fn(),
    onToggleReactMenu: vi.fn(),
    onReact: vi.fn(),
    onLeave: vi.fn(),
    onPreview: vi.fn(),
    ...overrides,
  };
  render(<RoomControlBar {...props} />);
  return props;
};

describe("RoomControlBar", () => {
  it("reports the current publish state through aria-pressed", () => {
    renderBar({ me: { mic: false, cam: true, hand: false } });

    expect(screen.getByTestId("room-microphone-toggle")).toHaveAttribute("aria-pressed", "false");
    expect(screen.getByTestId("room-camera-toggle")).toHaveAttribute("aria-pressed", "true");
  });

  it("delegates microphone and camera clicks to the room handlers", () => {
    const props = renderBar();

    fireEvent.click(screen.getByTestId("room-microphone-toggle"));
    fireEvent.click(screen.getByTestId("room-camera-toggle"));

    expect(props.onToggleMic).toHaveBeenCalledOnce();
    expect(props.onToggleCam).toHaveBeenCalledOnce();
  });

  it("disables the media toggles while the room cannot be controlled", () => {
    renderBar({ mediaDisabled: true });

    expect(screen.getByTestId("room-microphone-toggle")).toBeDisabled();
    expect(screen.getByTestId("room-camera-toggle")).toBeDisabled();
    expect(screen.getByTestId("room-microphone-select")).toBeDisabled();
    expect(screen.getByTestId("room-camera-select")).toBeDisabled();
  });

  it("announces only the device the instructor restricted", () => {
    renderBar({ microphoneBlocked: true });

    const notice = screen.getByTestId("room-publish-blocked");
    expect(notice).toHaveAttribute("role", "status");
    expect(notice.textContent).toContain("마이크");
    expect(notice.textContent).not.toContain("카메라");
  });

  it("disables only the restricted device toggle", () => {
    renderBar({ cameraBlocked: true });

    expect(screen.getByTestId("room-camera-toggle")).toBeDisabled();
    expect(screen.getByTestId("room-microphone-toggle")).toBeEnabled();
  });

  it("hides the restriction notice while publishing is allowed", () => {
    renderBar();

    expect(screen.queryByTestId("room-publish-blocked")).toBeNull();
  });

  it("reports the microphone picked from the device menu", async () => {
    const props = renderBar();

    fireEvent.click(screen.getByTestId("room-microphone-select"));
    fireEvent.click(await screen.findByText("이어폰 마이크"));

    expect(props.onSelectMicrophone).toHaveBeenCalledWith("mic-2");
  });

  it("disables the device menu when no device is available", () => {
    renderBar({ cameras: [], activeCameraId: null });

    expect(screen.getByTestId("room-camera-select")).toBeDisabled();
    expect(screen.getByTestId("room-camera-toggle")).toBeEnabled();
  });
});
