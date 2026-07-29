import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";

import { AnalysisStatusNotice } from "./AnalysisStatusNotice";

afterEach(() => {
  cleanup();
});

describe("AnalysisStatusNotice", () => {
  // 정상 동작을 배지로 알리면 수업 화면만 시끄러워진다.
  it("shows nothing while the analysis is running", () => {
    const { container } = render(<AnalysisStatusNotice availability="ACTIVE" />);

    expect(container).toBeEmptyDOMElement();
  });

  it("announces a paused analysis without explaining why", () => {
    render(<AnalysisStatusNotice availability="PAUSED" />);

    const notice = screen.getByTestId("analysis-status-notice");
    expect(notice).toHaveTextContent("학습 분석 일시 중지");
    // 카메라 안내는 프롬프트(81)가 원인별로 맡는다. 여기서 겹치면 안 된다.
    expect(notice).not.toHaveTextContent(/카메라/);
  });

  it("tells a detector failure apart from a camera outage", () => {
    render(<AnalysisStatusNotice availability="UNAVAILABLE" />);

    const notice = screen.getByTestId("analysis-status-notice");
    expect(notice).toHaveTextContent("학습 분석을 사용할 수 없어요");
    // 카메라 문제가 아니므로 카메라를 켜라고 안내해서는 안 된다.
    expect(notice).not.toHaveTextContent(/카메라/);
  });

  it("uses different copy for a paused analysis and a detector failure", () => {
    const paused = render(<AnalysisStatusNotice availability="PAUSED" />);
    const pausedText = paused.getByTestId("analysis-status-notice").textContent;
    cleanup();

    render(<AnalysisStatusNotice availability="UNAVAILABLE" />);

    expect(screen.getByTestId("analysis-status-notice").textContent).not.toBe(pausedText);
  });

  // 학생에게는 동작 여부만 보인다. 점수·등급·확률·개별 판정은 어디에도 없어야 한다.
  it.each(["PAUSED", "UNAVAILABLE"] as const)("keeps %s free of any judgement detail", (availability) => {
    render(<AnalysisStatusNotice availability={availability} />);

    const text = screen.getByTestId("analysis-status-notice").textContent ?? "";
    expect(text).not.toMatch(/\d/); // 점수·확률·등급 숫자
    expect(text).not.toMatch(/참여|집중|얼굴|점수|확률|등급/);
  });
});
