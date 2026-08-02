import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { ParticipantTile } from "./ParticipantTile";

describe("ParticipantTile", () => {
  it("announces an instructor's media and hand-raise status accessibly", () => {
    render(
      <ParticipantTile
        participant={{
          id: "instructor-1",
          name: "박서준",
          color: "#1cdd93",
          role: "instructor",
          cameraEnabled: true,
          microphoneEnabled: true,
          handRaised: false,
          speaking: false,
        }}
      />,
    );

    // 시각 배지는 없어도(티켓 246) 스크린리더용 역할 문구는 남아야 한다.
    expect(
      screen.getByRole("group", {
        name: "강사 박서준, 카메라 켜짐, 마이크 켜짐, 손 들지 않음",
      }),
    ).toBeVisible();
    expect(screen.queryByText("강사")).not.toBeInTheDocument();
  });

  it("wins the speaking green border over the instructor border while talking", () => {
    render(
      <ParticipantTile
        participant={{
          id: "instructor-1",
          name: "박서준",
          color: "#1cdd93",
          role: "instructor",
          cameraEnabled: true,
          microphoneEnabled: true,
          handRaised: false,
          speaking: true,
        }}
      />,
    );

    const tile = screen.getByRole("group", { name: /박서준/ });
    expect(tile.className).toContain("border-[#2fbf88]");
    expect(tile.className).not.toContain("border-primary");
  });

  it("returns to the instructor border when the speech ends", () => {
    render(
      <ParticipantTile
        participant={{
          id: "instructor-1",
          name: "박서준",
          color: "#1cdd93",
          role: "instructor",
          cameraEnabled: true,
          microphoneEnabled: true,
          handRaised: false,
          speaking: false,
        }}
      />,
    );

    const tile = screen.getByRole("group", { name: /박서준/ });
    expect(tile.className).toContain("border-primary");
    expect(tile.className).not.toContain("border-[#2fbf88]");
  });

  it("announces a student's disabled media and raised hand status", () => {
    render(
      <ParticipantTile
        participant={{
          id: "student-1",
          name: "이지은",
          color: "#f4c325",
          role: "student",
          cameraEnabled: false,
          microphoneEnabled: false,
          speaking: false,
          handRaised: true,
        }}
      />,
    );

    // 손들기 표시는 장식용 SVG라 텍스트가 없다. 상태는 타일의 접근성 이름으로만 알린다.
    expect(
      screen.getByRole("group", {
        name: "학생 이지은, 카메라 꺼짐, 마이크 꺼짐, 손 들음",
      }),
    ).toBeVisible();
  });

  it("shows red-slash icons only for the media that is turned off", () => {
    render(
      <ParticipantTile
        participant={{
          id: "student-1",
          name: "이지은",
          color: "#f4c325",
          role: "student",
          cameraEnabled: false,
          microphoneEnabled: false,
          speaking: false,
          handRaised: false,
        }}
      />,
    );

    expect(screen.getByTestId("tile-mic-off")).toBeInTheDocument();
    expect(screen.getByTestId("tile-camera-off")).toBeInTheDocument();
  });

  it("hides the off icons while the media is on", () => {
    render(
      <ParticipantTile
        participant={{
          id: "student-1",
          name: "이지은",
          color: "#f4c325",
          role: "student",
          cameraEnabled: true,
          microphoneEnabled: true,
          speaking: false,
          handRaised: false,
        }}
      />,
    );

    expect(screen.queryByTestId("tile-mic-off")).not.toBeInTheDocument();
    expect(screen.queryByTestId("tile-camera-off")).not.toBeInTheDocument();
  });

  it("keeps instructor-only participant controls for a student tile", () => {
    render(
      <ParticipantTile
        participant={{
          id: "student-1",
          name: "이지은",
          color: "#f4c325",
          role: "student",
          cameraEnabled: true,
          microphoneEnabled: true,
          speaking: false,
          handRaised: false,
        }}
        canControl
      />,
    );

    expect(screen.getByRole("button", { name: "이지은 음소거" })).toBeVisible();
    expect(screen.getByRole("button", { name: "이지은 퇴장" })).toBeVisible();
  });
});
