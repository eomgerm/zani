import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

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

  it("drops back to the plain border when the speech ends — even for the instructor", () => {
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

    // 강사 상시 테두리를 두면 발화 초록과 구분되지 않는다(피드백 반영). 테두리는 발화 표시 전용이다.
    const tile = screen.getByRole("group", { name: /박서준/ });
    expect(tile.className).not.toContain("border-[#2fbf88]");
    expect(tile.className).not.toContain("border-primary");
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

  it("shows only the mic-off icon in the name chip when media is off", () => {
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
    // 카메라 꺼짐은 아바타가 보이는 것으로 이미 드러나 칩에 아이콘을 두지 않는다(피드백 반영).
    expect(screen.queryByTestId("tile-camera-off")).not.toBeInTheDocument();
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

    // 퇴장은 기능 자체가 범위 밖이라 버튼을 두지 않는다(티켓 246). 음소거만 남는다.
    expect(screen.getByRole("button", { name: "이지은 음소거" })).toBeVisible();
    expect(screen.queryByRole("button", { name: "이지은 퇴장" })).not.toBeInTheDocument();
  });

  const student = (overrides: Record<string, unknown> = {}) => ({
    id: "student-1",
    name: "이지은",
    color: "#7c8cff",
    role: "student" as const,
    cameraEnabled: true,
    microphoneEnabled: true,
    handRaised: false,
    speaking: false,
    ...overrides,
  });

  /**
   * 아이콘이 아니라 글자를 쓴다. 이름칩의 MicOffIcon 이 "지금 음소거 상태"를 뜻해서, 같은 그림을
   * 버튼에 두면 상태와 동작이 한 그림에 겹친다.
   */
  it("음소거 버튼은 글자로 보인다", () => {
    render(<ParticipantTile participant={student()} canControl />);

    expect(screen.getByRole("button", { name: "이지은 음소거" })).toHaveTextContent("음소거");
  });

  it("누르면 강사 제어를 호출한다", () => {
    const onMute = vi.fn();
    render(<ParticipantTile participant={student()} canControl onMute={onMute} />);

    fireEvent.click(screen.getByRole("button", { name: "이지은 음소거" }));

    expect(onMute).toHaveBeenCalledOnce();
  });

  /** 강제 해제가 없어 이미 꺼진 마이크에는 할 일이 없다. 왜 못 누르는지 title 로 알린다. */
  it("이미 음소거면 누를 수 없다", () => {
    const onMute = vi.fn();
    render(
      <ParticipantTile
        participant={student({ microphoneEnabled: false })}
        canControl
        onMute={onMute}
      />,
    );

    const button = screen.getByRole("button", { name: "이지은 음소거" });
    expect(button).toBeDisabled();
    expect(button).toHaveAttribute("title", "이미 음소거됨");
    fireEvent.click(button);
    expect(onMute).not.toHaveBeenCalled();
  });

  it("요청 중에는 다시 누를 수 없다", () => {
    render(<ParticipantTile participant={student()} canControl muting />);

    const button = screen.getByRole("button", { name: "이지은 음소거" });
    expect(button).toBeDisabled();
    expect(button).toHaveAttribute("title", "음소거하는 중");
  });

  /** 다른 참가자를 처리하는 동안 눌러도 요청이 나가지 않는다. 버튼이 그 사실을 보여야 한다. */
  it("다른 대상을 처리하는 중이면 잠기고 처리 중이라고 알린다", () => {
    const onMute = vi.fn();
    render(<ParticipantTile participant={student()} canControl busy onMute={onMute} />);

    const button = screen.getByRole("button", { name: "이지은 음소거" });
    expect(button).toBeDisabled();
    expect(button).toHaveAttribute("title", "처리 중");
    fireEvent.click(button);
    expect(onMute).not.toHaveBeenCalled();
  });

  /** 학생 화면에는 제어 버튼이 없어야 한다. 권한은 서버가 최종 판단하지만 화면에 보일 이유가 없다. */
  it("제어 권한이 없으면 버튼이 없다", () => {
    render(<ParticipantTile participant={student()} />);

    expect(screen.queryByRole("button", { name: "이지은 음소거" })).not.toBeInTheDocument();
  });
});
