import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { ParticipantGrid } from "./ParticipantGrid";

const participant = (id: number) => ({
  id: `participant-${id}`,
  name: `참가자 ${id}`,
  color: "#1cdd93",
  role: id === 0 ? ("instructor" as const) : ("student" as const),
  cameraEnabled: true,
  microphoneEnabled: true,
  handRaised: false,
});

describe("ParticipantGrid", () => {
  it("renders every provided participant and exposes the rendered count", () => {
    render(<ParticipantGrid participants={Array.from({ length: 18 }, (_, index) => participant(index))} />);

    expect(screen.getByRole("group", { name: "참가자 18명" })).toBeVisible();
    expect(screen.getAllByRole("group", { name: /카메라 켜짐/ })).toHaveLength(18);
  });

  it("allows instructors to retain controls only for other students", () => {
    render(
      <ParticipantGrid
        currentParticipantId="instructor-1"
        isInstructor
        participants={[
          { ...participant(0), id: "instructor-1" },
          { ...participant(1), id: "student-1" },
        ]}
      />,
    );

    expect(screen.getByRole("button", { name: "참가자 1 음소거" })).toBeVisible();
    expect(screen.getByRole("button", { name: "참가자 1 퇴장" })).toBeVisible();
  });
});
