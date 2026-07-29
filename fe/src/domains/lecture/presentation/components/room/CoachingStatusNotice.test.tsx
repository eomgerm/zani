import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";

import { CoachingStatusNotice } from "./CoachingStatusNotice";

afterEach(() => {
  cleanup();
});

describe("CoachingStatusNotice", () => {
  // 팁은 10분에 한 번 수준이라 그동안 계속 "정상"을 띄우면 화면만 시끄러워진다.
  it("shows nothing while coaching works", () => {
    const { container } = render(<CoachingStatusNotice availability="ACTIVE" />);

    expect(container).toBeEmptyDOMElement();
  });

  it.each([
    ["TRANSCRIPTION_FAILED", "수업 음성을 인식하지 못하고 있어요"],
    ["TIP_FAILED", "수업 팁을 만들지 못하고 있어요"],
    ["POLL_FAILED", "수업 팁을 받아오지 못하고 있어요"],
  ] as const)("tells the instructor about %s", (availability, label) => {
    render(<CoachingStatusNotice availability={availability} />);

    expect(screen.getByTestId("coaching-status-notice")).toHaveTextContent(label);
  });

  it("uses different copy for each failure so the cause is distinguishable", () => {
    const labels = (["TRANSCRIPTION_FAILED", "TIP_FAILED", "POLL_FAILED"] as const).map(
      (availability) => {
        const view = render(<CoachingStatusNotice availability={availability} />);
        const text = view.getByTestId("coaching-status-notice").textContent;
        cleanup();
        return text;
      },
    );

    expect(new Set(labels).size).toBe(3);
  });

  // 집계 수치·학생 정보는 강사 화면에도 노출하지 않는다.
  it.each(["TRANSCRIPTION_FAILED", "TIP_FAILED", "POLL_FAILED"] as const)(
    "keeps %s free of any student detail",
    (availability) => {
      render(<CoachingStatusNotice availability={availability} />);

      const text = screen.getByTestId("coaching-status-notice").textContent ?? "";
      expect(text).not.toMatch(/\d/);
      expect(text).not.toMatch(/학생|이름|응답|비율/);
    },
  );
});
