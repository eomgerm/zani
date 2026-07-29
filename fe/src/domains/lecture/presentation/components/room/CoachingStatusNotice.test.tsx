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

  it("tells the instructor when tips cannot be fetched", () => {
    render(<CoachingStatusNotice availability="POLL_FAILED" />);

    expect(screen.getByTestId("coaching-status-notice")).toHaveTextContent(
      "수업 팁을 받아오지 못하고 있어요",
    );
  });

  // 집계 수치·학생 정보는 강사 화면에도 노출하지 않는다.
  it("keeps the notice free of any student detail", () => {
    render(<CoachingStatusNotice availability="POLL_FAILED" />);

    const text = screen.getByTestId("coaching-status-notice").textContent ?? "";
    expect(text).not.toMatch(/\d/);
    expect(text).not.toMatch(/학생|이름|응답|비율/);
  });
});
