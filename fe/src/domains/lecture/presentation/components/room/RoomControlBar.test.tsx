import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { RoomControlBar } from "./RoomControlBar";

const renderBar = (overrides: Partial<Parameters<typeof RoomControlBar>[0]> = {}) => {
  const props = {
    isInstructor: true,
    me: { mic: true, cam: true, hand: false },
    mediaDisabled: false,
    sharing: false,
    reactMenuOpen: false,
    onToggleMic: vi.fn(),
    onToggleCam: vi.fn(),
    onToggleShare: vi.fn(),
    onToggleHand: vi.fn(),
    onToggleReactMenu: vi.fn(),
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
  });
});
