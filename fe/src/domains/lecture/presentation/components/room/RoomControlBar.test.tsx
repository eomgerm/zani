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
    handDisabled: false,
    reactionDisabled: false,
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

  /**
   * 손들기는 내 상태를 알아야 올릴지 내릴지 정할 수 있어 participant identity 가 필요하지만, 반응은
   * 보낸 사람을 서버가 STOMP 주체에서 가져오므로 필요 없다. 한 조건으로 묶으면 LiveKit 이 붙기 전
   * 몇 초 동안 반응까지 막힌다.
   */
  it("손들기만 막혀도 반응은 보낼 수 있다", () => {
    renderBar({ handDisabled: true, reactionDisabled: false });

    expect(screen.getByLabelText("손들기")).toBeDisabled();
    expect(screen.getByLabelText("반응")).toBeEnabled();
  });

  it("채널이 끊기면 둘 다 막힌다", () => {
    renderBar({ handDisabled: true, reactionDisabled: true });

    expect(screen.getByLabelText("손들기")).toBeDisabled();
    expect(screen.getByLabelText("반응")).toBeDisabled();
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
