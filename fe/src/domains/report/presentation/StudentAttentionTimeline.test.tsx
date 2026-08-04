import type { ReactElement } from "react";
import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

vi.mock("@/domains/auth", () => ({ useAuth: () => ({ accessToken: "token" }) }));

// jsdom 은 레이아웃이 없어 ResponsiveContainer 가 0×0 으로 접힌다. 고정 크기로 바꿔 끼운다.
// 실제 ResponsiveContainer 도 자식에 width·height 를 주입하므로 같은 방식으로 흉내 낸다 —
// div 로만 감싸면 차트가 크기를 못 받아 path 를 아예 그리지 않는다.
vi.mock("recharts", async () => {
  const actual = await vi.importActual<typeof import("recharts")>("recharts");
  const { cloneElement } = await import("react");
  return {
    ...actual,
    ResponsiveContainer: ({ children }: { children: ReactElement }) =>
      cloneElement(children as ReactElement<{ width?: number; height?: number }>, {
        width: 800,
        height: 300,
      }),
  };
});

import { StudentAttentionTimeline } from "./StudentAttentionTimeline";
import {
  AttentionTimelineError,
  type StudentAttentionTimeline as StudentAttentionTimelineData,
} from "../infrastructure/attentionTimelineApi";

const timelineWith = (
  overrides: Partial<StudentAttentionTimelineData> = {},
): StudentAttentionTimelineData => ({
  durationSeconds: 90,
  focusFlow: {
    intervalSeconds: 30,
    points: [
      { offsetSeconds: 0, focusLevel: 3.67 },
      { offsetSeconds: 30, focusLevel: null },
      { offsetSeconds: 60, focusLevel: 2.0 },
    ],
  },
  stateIntervals: [{ startSeconds: 0, endSeconds: 90, state: "GOOD" }],
  sections: [],
  ...overrides,
});

describe("StudentAttentionTimeline", () => {
  it("1~4 축으로 그린다 — 퍼센트를 쓰지 않는다", async () => {
    render(<StudentAttentionTimeline sessionId="s1" request={async () => timelineWith()} />);

    const chart = await screen.findByRole("img");

    expect(chart.getAttribute("aria-label")).toContain("1~4 단계");
    expect(chart.getAttribute("aria-label")).not.toContain("%");
  });

  it("설명에 이동창이라고 쓰지 않는다 — 겹치지 않는 30초 구간이다", async () => {
    const { container } = render(
      <StudentAttentionTimeline sessionId="s1" request={async () => timelineWith()} />,
    );

    await screen.findByRole("img");

    const chart = screen.getByRole("img");
    expect(container.textContent).not.toContain("이동창");
    expect(chart.getAttribute("aria-label")).toContain("30초 구간");
  });

  it("빈 값 구간을 공백으로 알린다 — 0% 도 1단계도 아니다", async () => {
    const { container } = render(
      <StudentAttentionTimeline sessionId="s1" request={async () => timelineWith()} />,
    );

    const chart = await screen.findByRole("img");

    // 30~60초 한 칸이 비어 있다.
    expect(chart.getAttribute("aria-label")).toContain("00:30");
    // recharts 는 값이 null 인 점을 path 에서 끊는다. 0 으로 채웠다면 끊기지 않는다.
    const path = container.querySelector("path.recharts-area-curve");
    expect(path?.getAttribute("d")).toContain("M");
  });

  /** 집중 흐름 칸에는 그래프만 둔다. 상태 막대는 타임라인이 구간 선택을 맡으면서 걷어냈다. */
  it("집중 흐름 칸에 상태 막대를 그리지 않는다", async () => {
    render(
      <StudentAttentionTimeline
        sessionId="s1"
        request={async () =>
          timelineWith({
            stateIntervals: [
              { startSeconds: 0, endSeconds: 30, state: "GOOD" },
              { startSeconds: 30, endSeconds: 60, state: "CAMERA_OFF" },
            ],
          })
        }
      />,
    );

    await screen.findByRole("img");

    expect(screen.queryByRole("group", { name: /상태/ })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /카메라 꺼짐/ })).not.toBeInTheDocument();
  });

  it("내용 구간 평균이 있으면 함께 보여준다", async () => {
    render(
      <StudentAttentionTimeline
        sessionId="s1"
        request={async () =>
          timelineWith({
            sections: [{ startSeconds: 0, endSeconds: 90, title: "함수의 정의", focusLevel: 3.0 }],
          })
        }
      />,
    );

    expect(await screen.findByText("함수의 정의")).toBeInTheDocument();
  });

  it("내용 구간이 없으면 그 영역만 비고 그래프는 정상이다", async () => {
    const { container } = render(
      <StudentAttentionTimeline sessionId="s1" request={async () => timelineWith()} />,
    );

    expect(await screen.findByRole("img")).toBeInTheDocument();
    expect(screen.queryByText("수업 내용 구간")).not.toBeInTheDocument();
    // 구간이 없어도 흐름은 한 줄로 그린다.
    expect(container.querySelectorAll("path.recharts-area-curve")).toHaveLength(1);
  });

  /** 구간을 색으로 갈라 놓지 않으면 어느 내용에서 흐름이 내려갔는지 시간축을 되짚어야 한다. */
  it("내용 구간마다 계열을 따로 그리고 구간 이름을 붙인다", async () => {
    const { container } = render(
      <StudentAttentionTimeline
        sessionId="s1"
        request={async () =>
          timelineWith({
            sections: [
              { startSeconds: 0, endSeconds: 30, title: "도입", focusLevel: 3.4 },
              { startSeconds: 30, endSeconds: 90, title: "실습", focusLevel: 1.8 },
            ],
          })
        }
      />,
    );

    await screen.findByRole("img");

    // 구간마다 계열이 하나씩 — 한 줄로 이어 그리지 않는다.
    expect(container.querySelectorAll("path.recharts-area-curve")).toHaveLength(2);
    // 구간 이름은 차트 위에 붙고, 아래 카드도 같은 번호를 쓴다.
    expect(screen.getAllByText("구간 1").length).toBeGreaterThan(0);
    expect(screen.getAllByText("구간 2").length).toBeGreaterThan(0);
    // 평균이 다른 두 구간은 색도 다르다.
    const strokes = [...container.querySelectorAll("path.recharts-area-curve")].map((path) =>
      path.getAttribute("stroke"),
    );
    expect(strokes[0]).not.toBe(strokes[1]);
  });

  it("구간 경계마다 점선을 하나씩 둔다 — 첫 구간의 시작은 축과 겹쳐 그리지 않는다", async () => {
    const { container } = render(
      <StudentAttentionTimeline
        sessionId="s1"
        request={async () =>
          timelineWith({
            sections: [
              { startSeconds: 0, endSeconds: 30, title: "도입", focusLevel: 3.4 },
              { startSeconds: 30, endSeconds: 90, title: "실습", focusLevel: 1.8 },
            ],
          })
        }
      />,
    );

    await screen.findByRole("img");

    const dashed = [...container.querySelectorAll("line.recharts-reference-line-line")].filter(
      (line) => line.getAttribute("stroke-dasharray") === "4 5",
    );
    expect(dashed).toHaveLength(1);
  });

  it("내용 구간이 하나도 없어도 깨지지 않는다", async () => {
    render(<StudentAttentionTimeline sessionId="s1" request={async () => timelineWith()} />);

    expect(await screen.findByRole("img")).toBeInTheDocument();
    expect(screen.getByText(/수업 내용 구간이 아직 없어요/)).toBeInTheDocument();
  });

  it("labels the metric as a reference-only derived value", async () => {
    render(<StudentAttentionTimeline sessionId="s1" request={async () => timelineWith()} />);

    // NFR-UX-006. 이 고지가 없으면 학생이 성적표로 읽는다. 긴 문장은 걷어내고 범례 칩에 남겼다.
    expect(await screen.findByText(/참고용/)).toBeInTheDocument();
  });

  it("never shows an average score or a comparison with others", async () => {
    render(<StudentAttentionTimeline sessionId="s1" request={async () => timelineWith()} />);
    await screen.findByRole("img");

    // REPORT-S-010 이 금지하는 것은 전체 점수와 타인 비교다. "단계 평균" 은 지표 자체의 정의라
    // 문구에 남아야 한다(설계 문서 §2.8) — "평균" 이라는 낱말만으로 걸러내지 않는다.
    expect(screen.queryByText(/전체 평균|평균 점수|내 점수/)).not.toBeInTheDocument();
    expect(screen.queryByText(/상위|하위|비교/)).not.toBeInTheDocument();
  });

  it("explains a 409 as a class that is still running", async () => {
    const request = vi.fn().mockRejectedValue(new AttentionTimelineError("live", 409));
    render(<StudentAttentionTimeline sessionId="s1" request={request} />);

    expect(await screen.findByText(/진행 중/)).toBeInTheDocument();
  });

  it("shows an empty state when there are no observations", async () => {
    render(
      <StudentAttentionTimeline
        sessionId="s1"
        request={async () =>
          timelineWith({ durationSeconds: 0, focusFlow: { intervalSeconds: 30, points: [] } })
        }
      />,
    );

    expect(await screen.findByText(/기록이 없어요|기록이 없습니다/)).toBeInTheDocument();
  });
});
