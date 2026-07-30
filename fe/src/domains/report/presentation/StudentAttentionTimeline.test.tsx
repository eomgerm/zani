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
import { AttentionTimelineError } from "../infrastructure/attentionTimelineApi";

const timeline = {
  intervalSeconds: 5,
  durationSeconds: 20,
  points: [
    { offsetSeconds: 0, focusPercent: 90, state: "GOOD" as const },
    { offsetSeconds: 5, focusPercent: null, state: "CAMERA_OFF" as const },
    { offsetSeconds: 10, focusPercent: null, state: "UNMEASURABLE" as const },
    { offsetSeconds: 15, focusPercent: 70, state: "GOOD" as const },
  ],
};

describe("StudentAttentionTimeline", () => {
  it("draws empty values as gaps instead of zero", async () => {
    const { container } = render(
      <StudentAttentionTimeline sessionId="s1" request={vi.fn().mockResolvedValue(timeline)} />,
    );

    await screen.findByRole("img", { name: /집중 흐름/ });

    // recharts 는 값이 null 인 점을 path 에서 끊는다. 0 으로 채웠다면 끊기지 않는다.
    const path = container.querySelector("path.recharts-line-curve");
    expect(path?.getAttribute("d")).toContain("M");
    expect(screen.getByText(/측정 불가/)).toBeInTheDocument();
  });

  it("labels the metric as a reference-only derived value", async () => {
    render(<StudentAttentionTimeline sessionId="s1" request={vi.fn().mockResolvedValue(timeline)} />);

    // NFR-UX-006. 이 문구가 없으면 학생이 성적표로 읽는다.
    expect(await screen.findByText(/참고용/)).toBeInTheDocument();
  });

  it("never shows an average score or a comparison with others", async () => {
    render(<StudentAttentionTimeline sessionId="s1" request={vi.fn().mockResolvedValue(timeline)} />);
    await screen.findByRole("img", { name: /집중 흐름/ });

    expect(screen.queryByText(/평균/)).not.toBeInTheDocument();
    expect(screen.queryByText(/상위|하위|비교/)).not.toBeInTheDocument();
  });

  it("explains a 409 as a class that is still running", async () => {
    const request = vi.fn().mockRejectedValue(new AttentionTimelineError("live", 409));
    render(<StudentAttentionTimeline sessionId="s1" request={request} />);

    expect(await screen.findByText(/진행 중/)).toBeInTheDocument();
  });

  it("shows an empty state when there are no observations", async () => {
    const request = vi.fn().mockResolvedValue({ intervalSeconds: 5, durationSeconds: 0, points: [] });
    render(<StudentAttentionTimeline sessionId="s1" request={request} />);

    expect(await screen.findByText(/기록이 없어요|기록이 없습니다/)).toBeInTheDocument();
  });
});
