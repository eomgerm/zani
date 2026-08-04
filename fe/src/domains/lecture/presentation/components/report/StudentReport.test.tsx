import type { ReactElement } from "react";
import { fireEvent, render, screen } from "@testing-library/react";
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

import { StudentReport } from "./StudentReport";
import {
  StudentReportError,
  type StudentReport as StudentReportData,
} from "@/domains/report/infrastructure/studentReportApi";
import {
  AttentionTimelineError,
  type StudentAttentionTimeline as TimelineData,
} from "@/domains/report/infrastructure/attentionTimelineApi";
import { QuizSummaryError } from "@/domains/report/infrastructure/quizSummaryApi";

const reportWith = (overrides: Partial<StudentReportData> = {}): StudentReportData => ({
  activity: { publicChatCount: 3, confusedCount: 2, missedCount: 1 },
  participationSummary: "공개 채팅으로 질문을 남겼어요.",
  recommendations: [
    {
      recommendationType: "CONFUSED",
      title: "useMemo 메모이제이션 패턴",
      description: "‘헷갈려요’ 로 답한 구간이에요.",
      startSeconds: 1450,
      endSeconds: 1500,
    },
  ],
  ...overrides,
});

const timelineWith = (overrides: Partial<TimelineData> = {}): TimelineData => ({
  durationSeconds: 120,
  focusFlow: {
    intervalSeconds: 30,
    points: [
      { offsetSeconds: 0, focusLevel: 3.5 },
      { offsetSeconds: 30, focusLevel: 2.6 },
      { offsetSeconds: 60, focusLevel: 1.4 },
      { offsetSeconds: 90, focusLevel: null },
    ],
  },
  stateIntervals: [],
  sections: [],
  ...overrides,
});

const renderReport = (
  props: Partial<Parameters<typeof StudentReport>[0]> = {},
) =>
  render(
    <StudentReport
      lectureId="L1"
      sessionId="s1"
      onJumpToClip={() => {}}
      reportRequest={async () => reportWith()}
      attentionRequest={async () => timelineWith()}
      quizRequest={async () => ({ questionCount: 5, estimatedDurationMinutes: 3 })}
      {...props}
    />,
  );

describe("StudentReport", () => {
  it("한눈에 보기에 집중 구간 비율과 활동 집계를 그린다", async () => {
    renderReport();

    // 측정 가능한 세 칸 중 2.5 이상이 둘 → 67%. 값 없는 칸은 분모에서 뺀다.
    expect(await screen.findByText("67%")).toBeInTheDocument();
    expect(await screen.findByText("3회")).toBeInTheDocument();
    expect(screen.getByText("2회")).toBeInTheDocument();
    expect(screen.getByText("1회")).toBeInTheDocument();
    expect(screen.getByText("공개 채팅")).toBeInTheDocument();
  });

  it("참여도 요약을 서버 문장 그대로 보여준다", async () => {
    renderReport();

    expect(await screen.findByText("공개 채팅으로 질문을 남겼어요.")).toBeInTheDocument();
  });

  it("복습 추천의 근거 유형을 249 라벨로 읽고 시작 시각을 클립으로 넘긴다", async () => {
    const onJumpToClip = vi.fn();
    renderReport({ onJumpToClip });

    expect(await screen.findByText("헷갈림")).toBeInTheDocument();
    expect(screen.getByText("24:10")).toBeInTheDocument();
    expect(screen.getByText("‘헷갈려요’ 로 답한 구간이에요.")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: /useMemo 메모이제이션 패턴/ }));

    expect(onJumpToClip).toHaveBeenCalledWith(1450);
  });

  it("모르는 근거 유형도 버리지 않고 중립 문구로 그린다", async () => {
    renderReport({
      reportRequest: async () =>
        reportWith({
          recommendations: [
            {
              recommendationType: "NEW_SIGNAL",
              title: "새 신호",
              description: "근거",
              startSeconds: 0,
              endSeconds: 30,
            },
          ],
        }),
    });

    expect(await screen.findByText("복습 추천")).toBeInTheDocument();
    expect(screen.getByText("새 신호")).toBeInTheDocument();
  });

  it("추천이 없으면 오류가 아니라 없음으로 알린다(REPORT-S-005)", async () => {
    renderReport({ reportRequest: async () => reportWith({ recommendations: [] }) });

    expect(await screen.findByText(/복습 추천이 없어요/)).toBeInTheDocument();
  });

  it.each([
    [409, "수업이 끝나면 학습 리포트를 볼 수 있어요"],
    [404, "아직 학습 리포트가 준비되지 않았어요"],
    [403, "이 수업의 학습 리포트를 볼 수 없어요"],
  ])("학습 리포트 %s 는 상태에 맞는 안내를 낸다", async (status, message) => {
    renderReport({
      reportRequest: async () => {
        throw new StudentReportError("no", status);
      },
    });

    expect((await screen.findAllByText(message)).length).toBeGreaterThan(0);
    // 리포트가 없어도 활동 집계 자리는 빈 자리로 둔다 — 0회로 적으면 "안 했다"가 된다.
    expect(screen.getAllByText("—").length).toBe(3);
  });

  it("학습 리포트 실패는 다시 시도를 준다", async () => {
    let calls = 0;
    renderReport({
      reportRequest: async () => {
        calls += 1;
        if (calls === 1) throw new StudentReportError("boom", 500);
        return reportWith();
      },
    });

    const retry = (await screen.findAllByRole("button", { name: "다시 시도" }))[0];
    fireEvent.click(retry);

    expect(await screen.findByText("공개 채팅으로 질문을 남겼어요.")).toBeInTheDocument();
  });

  it("집중 흐름만 실패하면 비율 자리에만 상태를 적는다 — 리포트는 그대로 그린다", async () => {
    renderReport({
      attentionRequest: async () => {
        throw new AttentionTimelineError("boom", 500);
      },
    });

    expect(await screen.findByText("불러오기 실패")).toBeInTheDocument();
    expect(screen.getByText("공개 채팅으로 질문을 남겼어요.")).toBeInTheDocument();
  });

  it("측정 가능한 칸이 없으면 0% 가 아니라 측정 불가다(REPORT-S-007)", async () => {
    renderReport({
      attentionRequest: async () =>
        timelineWith({
          focusFlow: { intervalSeconds: 30, points: [{ offsetSeconds: 0, focusLevel: null }] },
        }),
    });

    expect(await screen.findByText("측정 불가")).toBeInTheDocument();
  });

  it("퀴즈 요약이 오면 문항 수와 예상 시간을 적는다", async () => {
    renderReport();

    expect(await screen.findByText("총 5문제 · 약 3분")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "퀴즈 풀어보기" })).toHaveAttribute(
      "href",
      "/my-lectures/L1/quiz",
    );
  });

  it("예상 시간이 없으면 문항 수만 적는다 — '약 0분' 을 쓰지 않는다", async () => {
    renderReport({
      quizRequest: async () => ({ questionCount: 4, estimatedDurationMinutes: null }),
    });

    expect(await screen.findByText("총 4문제")).toBeInTheDocument();
  });

  it("퀴즈가 없으면 풀기 버튼을 내지 않는다 — 눌러도 빈 화면뿐이다", async () => {
    renderReport({
      quizRequest: async () => {
        throw new QuizSummaryError("missing", 404);
      },
    });

    expect(await screen.findByText("아직 퀴즈가 준비되지 않았어요")).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "퀴즈 풀어보기" })).not.toBeInTheDocument();
  });

  it("집중 흐름 응답을 한 번만 조회해 비율 타일과 차트가 함께 쓴다", async () => {
    const attentionRequest = vi.fn(async () => timelineWith());
    renderReport({ attentionRequest });

    await screen.findByText("67%");

    expect(attentionRequest).toHaveBeenCalledTimes(1);
  });
});
