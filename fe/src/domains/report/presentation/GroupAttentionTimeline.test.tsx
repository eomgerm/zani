import type { ReactElement } from "react";
import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

vi.mock("@/domains/auth", () => ({ useAuth: () => ({ accessToken: "token" }) }));

// jsdom 은 레이아웃이 없어 ResponsiveContainer 가 0×0 으로 접힌다. 고정 크기로 바꿔 끼운다.
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

import { GroupAttentionTimeline } from "./GroupAttentionTimeline";
import {
  AttentionTimelineError,
  type GroupAttentionTimeline as GroupAttentionTimelineData,
} from "../infrastructure/attentionTimelineApi";

const groupTimelineWith = (
  overrides: Partial<GroupAttentionTimelineData> = {},
): GroupAttentionTimelineData => ({
  durationSeconds: 60,
  focusFlow: {
    intervalSeconds: 30,
    points: [
      { offsetSeconds: 0, focusLevel: 3.2, eligibleCount: 28 },
      { offsetSeconds: 30, focusLevel: 2.4, eligibleCount: 28 },
    ],
  },
  signals: {
    intervalSeconds: 5,
    points: [
      {
        offsetSeconds: 0,
        connectedCount: 30,
        eligibleCount: 28,
        checkNeededRatio: 0.32,
        cameraOffRatio: 0.07,
        confusedRatio: 0.1,
        missedRatio: 0.1,
        nonResponseRatio: 0.07,
        unmeasurableRatio: 0.05,
      },
      {
        offsetSeconds: 5,
        connectedCount: 30,
        eligibleCount: 28,
        checkNeededRatio: 0.28,
        cameraOffRatio: 0.07,
        confusedRatio: 0.08,
        missedRatio: 0.1,
        nonResponseRatio: 0.1,
        unmeasurableRatio: 0.05,
      },
    ],
  },
  distractedIntervals: [],
  sections: [],
  ...overrides,
});

describe("GroupAttentionTimeline", () => {
  it("카드 제목이 집중 흐름이다", async () => {
    render(<GroupAttentionTimeline sessionId="s1" request={async () => groupTimelineWith()} />);

    // 제목 그 자체만 짚는다. 본문 문구에도 "집중 흐름" 이 나오므로 정확히 일치하는 것을 찾는다.
    expect(await screen.findByText("집중 흐름")).toBeInTheDocument();
  });

  it("집단 집중 흐름이 주 계열이라 설명 맨 앞에 온다", async () => {
    render(<GroupAttentionTimeline sessionId="s1" request={async () => groupTimelineWith()} />);

    const ariaLabel = (await screen.findByRole("img")).getAttribute("aria-label") ?? "";

    expect(ariaLabel.indexOf("집중 흐름")).toBeGreaterThanOrEqual(0);
    expect(ariaLabel.indexOf("집중 흐름")).toBeLessThan(ariaLabel.indexOf("확인 필요"));
  });

  /** 강사 그래프도 학생과 같은 구성이다 — 집단 집중 흐름 한 계열만 그린다. */
  it("집단 집중 흐름 한 계열만 그린다", async () => {
    const { container } = render(
      <GroupAttentionTimeline sessionId="s1" request={async () => groupTimelineWith()} />,
    );

    await screen.findByRole("img");

    expect(container.querySelectorAll("path.recharts-area-curve")).toHaveLength(1);
    expect(container.querySelector('path[stroke="#16c582"]')?.getAttribute("d")).toContain("M");
  });

  it("척도가 1~4 단계라는 것을 그래프 이름에 적는다", async () => {
    render(<GroupAttentionTimeline sessionId="s1" request={async () => groupTimelineWith()} />);

    const chart = await screen.findByRole("img");

    expect(chart.getAttribute("aria-label")).toContain("1~4 단계");
    // 비율 계열이 빠졌으므로 퍼센트 축도 없다.
    expect(chart.getAttribute("aria-label")).not.toContain("%");
  });

  it("집계 인원이 5명 미만인 30초 칸을 안내한다", async () => {
    render(
      <GroupAttentionTimeline
        sessionId="s1"
        request={async () =>
          groupTimelineWith({
            focusFlow: {
              intervalSeconds: 30,
              points: [{ offsetSeconds: 0, focusLevel: null, eligibleCount: 4 }],
            },
          })
        }
      />,
    );

    expect(await screen.findByText(/집계 인원이 부족합니다/)).toBeInTheDocument();
  });

  it("학생 이름이나 개별 값이 화면에 없다", async () => {
    const { container } = render(
      <GroupAttentionTimeline sessionId="s1" request={async () => groupTimelineWith()} />,
    );

    await screen.findByRole("img");

    // REPORT-I-002. 익명 그래프에서 개인으로 가는 길을 만들지 않는다.
    for (const forbidden of ["participantId", "memberId", "studentId", "displayName"]) {
      expect(container.innerHTML).not.toContain(forbidden);
    }
    expect(screen.queryByText(/학생 \d|님|이름/)).not.toBeInTheDocument();
    expect(screen.queryByRole("link", { name: /학생/ })).not.toBeInTheDocument();
  });

  it("내용 구간 평균을 함께 보여준다", async () => {
    render(
      <GroupAttentionTimeline
        sessionId="s1"
        request={async () =>
          groupTimelineWith({
            sections: [{ startSeconds: 0, endSeconds: 60, title: "함수의 정의", focusLevel: 2.8 }],
          })
        }
      />,
    );

    expect(await screen.findByText("함수의 정의")).toBeInTheDocument();
  });

  it("관측이 없으면 빈 상태를 안내한다", async () => {
    render(
      <GroupAttentionTimeline
        sessionId="s1"
        request={async () =>
          groupTimelineWith({
            durationSeconds: 0,
            focusFlow: { intervalSeconds: 30, points: [] },
            signals: { intervalSeconds: 5, points: [] },
          })
        }
      />,
    );

    expect(await screen.findByText(/기록이 없어요/)).toBeInTheDocument();
  });

  it("shows the response mix for the selected interval", async () => {
    render(<GroupAttentionTimeline sessionId="s1" request={async () => groupTimelineWith()} />);

    expect(await screen.findByText(/헷갈림/)).toBeInTheDocument();
    expect(screen.getByText(/놓침/)).toBeInTheDocument();
    expect(screen.getByText(/무응답/)).toBeInTheDocument();
    expect(screen.getByText(/측정 불가/)).toBeInTheDocument();
  });

  it("explains a 403 as a permission problem, not an empty report", async () => {
    const request = vi.fn().mockRejectedValue(new AttentionTimelineError("nope", 403));
    render(<GroupAttentionTimeline sessionId="s1" request={request} />);

    expect(await screen.findByText(/권한/)).toBeInTheDocument();
  });

  it("offers a retry when the request fails", async () => {
    const request = vi.fn().mockRejectedValue(new AttentionTimelineError("boom", 0));
    render(<GroupAttentionTimeline sessionId="s1" request={request} />);

    expect(await screen.findByRole("button", { name: "다시 시도" })).toBeInTheDocument();
  });
});
