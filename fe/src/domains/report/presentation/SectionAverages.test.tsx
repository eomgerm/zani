import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { SectionAverages } from "./SectionAverages";

describe("SectionAverages", () => {
  it("구간마다 제목·시각·평균을 보여준다", () => {
    render(
      <SectionAverages
        sections={[
          { startSeconds: 0, endSeconds: 372, title: "함수의 정의", focusLevel: 3.21 },
          { startSeconds: 372, endSeconds: 900, title: "합성 함수", focusLevel: 2.5 },
        ]}
      />,
    );

    expect(screen.getByText("함수의 정의")).toBeInTheDocument();
    expect(screen.getByText("합성 함수")).toBeInTheDocument();
    expect(screen.getByText("3.21")).toBeInTheDocument();
    expect(screen.getByText("2.50")).toBeInTheDocument();
  });

  it("경계가 고정 길이가 아닌 실제 시각으로 보인다", () => {
    render(
      <SectionAverages
        sections={[{ startSeconds: 0, endSeconds: 372, title: "함수의 정의", focusLevel: 3.21 }]}
      />,
    );

    // 372초 = 06:12. 10분 같은 고정 길이가 아니다.
    expect(screen.getByText(/06:12/)).toBeInTheDocument();
  });

  it("값이 없는 구간은 1단계가 아니라 값 없음으로 보인다", () => {
    render(
      <SectionAverages
        sections={[{ startSeconds: 0, endSeconds: 90, title: "쉬는 시간", focusLevel: null }]}
      />,
    );

    expect(screen.getByText("값 없음")).toBeInTheDocument();
    expect(screen.queryByText("1.00")).not.toBeInTheDocument();
  });

  it("구간이 없으면 아무것도 그리지 않는다 — 248 미완 세션", () => {
    const { container } = render(<SectionAverages sections={[]} />);

    expect(container).toBeEmptyDOMElement();
  });

  it("퍼센트 기호를 쓰지 않는다 — 1~4 척도다", () => {
    const { container } = render(
      <SectionAverages
        sections={[{ startSeconds: 0, endSeconds: 90, title: "함수의 정의", focusLevel: 3.21 }]}
      />,
    );

    expect(container.textContent).not.toContain("%");
  });
});
