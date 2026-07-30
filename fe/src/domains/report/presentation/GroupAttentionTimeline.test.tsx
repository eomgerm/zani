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
import { AttentionTimelineError } from "../infrastructure/attentionTimelineApi";

const timeline = {
  intervalSeconds: 5,
  durationSeconds: 20,
  points: [
    {
      offsetSeconds: 0,
      connectedCount: 10,
      eligibleCount: 8,
      checkNeededRatio: 0.25,
      cameraOffRatio: 0.2,
      confusedRatio: 0.125,
      missedRatio: 0.125,
      nonResponseRatio: 0,
      unmeasurableRatio: 0,
    },
    {
      offsetSeconds: 5,
      connectedCount: 4,
      eligibleCount: 4,
      checkNeededRatio: null,
      cameraOffRatio: null,
      confusedRatio: null,
      missedRatio: null,
      nonResponseRatio: null,
      unmeasurableRatio: null,
    },
  ],
  distractedIntervals: [{ startSeconds: 0, endSeconds: 10 }],
};

describe("GroupAttentionTimeline", () => {
  it("shows a shortage notice instead of a ratio below five students", async () => {
    render(<GroupAttentionTimeline sessionId="s1" request={vi.fn().mockResolvedValue(timeline)} />);

    expect(await screen.findByText(/집계 인원이 부족합니다/)).toBeInTheDocument();
  });

  it("never shows a student name or an individual state", async () => {
    render(<GroupAttentionTimeline sessionId="s1" request={vi.fn().mockResolvedValue(timeline)} />);
    await screen.findByRole("img", { name: /집단/ });

    // REPORT-I-002. 익명 그래프에서 개인으로 가는 길을 만들지 않는다.
    expect(screen.queryByText(/학생 \d|님|이름/)).not.toBeInTheDocument();
    expect(screen.queryByRole("link", { name: /학생/ })).not.toBeInTheDocument();
  });

  it("states that the two ratios use different denominators", async () => {
    render(<GroupAttentionTimeline sessionId="s1" request={vi.fn().mockResolvedValue(timeline)} />);

    expect(await screen.findByText(/분모가 다릅니다|더하지/)).toBeInTheDocument();
  });

  it("shows the response mix for the selected interval", async () => {
    render(<GroupAttentionTimeline sessionId="s1" request={vi.fn().mockResolvedValue(timeline)} />);

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
});
