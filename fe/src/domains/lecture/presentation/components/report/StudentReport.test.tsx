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
// report 도메인은 공개 API 로만 가져온다. infrastructure 를 직접 열면 경계가 무의미해진다.
import {
  AttentionTimelineError,
  StudentQuizError,
  StudentReportError,
  type StudentAttentionTimelineData as TimelineData,
  type StudentQuiz,
  type StudentReportData,
} from "@/domains/report";

/** 카드는 문항 수와 예상 시간만 읽는다. 문항 본문은 퀴즈 화면의 관심사다. */
const quizWith = (questionCount: number, estimatedDurationMinutes: number | null): StudentQuiz => ({
  title: "퀴즈",
  description: "",
  estimatedDurationMinutes,
  submitted: false,
  questions: Array.from({ length: questionCount }, (_, index) => ({
    questionId: `q${index}`,
    order: index + 1,
    text: `문항 ${index + 1}`,
    options: [{ optionId: `q${index}o1`, order: 1, text: "보기" }],
    grading: null,
  })),
});

const reportWith = (overrides: Partial<StudentReportData> = {}): StudentReportData => ({
  activity: { publicChatCount: 3, confusedCount: 2, missedCount: 1, questionCount: 2 },
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
      sessionId="s1"
      onJumpToClip={() => {}}
      reportRequest={async () => reportWith()}
      attentionRequest={async () => timelineWith()}
      quizRequest={async () => quizWith(5, 3)}
      {...props}
    />,
  );

describe("StudentReport", () => {
  it("한눈에 보기에 집중 구간 비율과 활동 집계를 그린다", async () => {
    renderReport();

    // 측정 가능한 세 칸 중 2.5 이상이 둘 → 67%. 값 없는 칸은 분모에서 뺀다.
    expect(await screen.findByText("67%")).toBeInTheDocument();
    // 질문 수는 개, 프롬프트 응답 집계는 회로 센다.
    expect(await screen.findByText("2개")).toBeInTheDocument();
    expect(screen.getByText("질문 수")).toBeInTheDocument();
    expect(screen.getByText("2회")).toBeInTheDocument();
    expect(screen.getByText("1회")).toBeInTheDocument();
  });

  it("질문 수 판정이 없으면 0개가 아니라 빈 자리다", async () => {
    renderReport({
      reportRequest: async () =>
        reportWith({
          activity: { publicChatCount: 3, confusedCount: 2, missedCount: 1, questionCount: null },
        }),
    });

    // 나머지 집계는 그대로 나온다 — 한 칸이 비었다고 카드를 통째로 비우지 않는다.
    expect(await screen.findByText("2회")).toBeInTheDocument();
    expect(screen.getByText("—")).toBeInTheDocument();
    expect(screen.queryByText("0개")).not.toBeInTheDocument();
  });

  it("질문 수가 0 이면 0개로 적는다 — 판정이 없는 것과 다르다", async () => {
    renderReport({
      reportRequest: async () =>
        reportWith({
          activity: { publicChatCount: 3, confusedCount: 2, missedCount: 1, questionCount: 0 },
        }),
    });

    expect(await screen.findByText("0개")).toBeInTheDocument();
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
    // 카드는 배지·시각·제목까지만 읽는다. 설명은 카드를 늘려 목록을 짧게 만들어 뺐다.
    expect(screen.queryByText("‘헷갈려요’ 로 답한 구간이에요.")).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: /useMemo 메모이제이션 패턴/ }));

    expect(onJumpToClip).toHaveBeenCalledWith(1450);
  });

  it("다섯 가지 근거를 각자의 문구와 색으로 그린다", async () => {
    const types = ["CONFUSED", "MISSED", "NO_RESPONSE", "LOW_ENGAGEMENT", "QUESTION"];
    renderReport({
      reportRequest: async () =>
        reportWith({
          recommendations: types.map((type, index) => ({
            recommendationType: type,
            title: `추천 ${index}`,
            description: "근거",
            startSeconds: index * 60,
            endSeconds: index * 60 + 30,
          })),
        }),
    });

    // 문구와 색이 짝을 이룬다. 색만으로 구분하지 않으므로 문구가 먼저다(FRD §19.2).
    const expected: [string, string][] = [
      ["헷갈림", "rgb(138, 106, 16)"],
      ["놓침", "rgb(161, 84, 28)"],
      ["무응답", "rgb(95, 101, 138)"],
      ["집중 저하", "rgb(179, 36, 58)"],
      ["내 질문", "rgb(22, 134, 94)"],
    ];
    for (const [label, color] of expected) {
      const badge = await screen.findByText(label);
      expect(badge).toBeInTheDocument();
      expect(badge.style.color).toBe(color);
    }

    // 다섯 색이 서로 겹치지 않아야 눈으로 갈라 볼 수 있다.
    const backgrounds = await Promise.all(
      expected.map(async ([label]) => (await screen.findByText(label)).style.background),
    );
    expect(new Set(backgrounds).size).toBe(5);
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
    // 퀴즈 라우트가 받는 것은 세션 id 다. 조회에 쓴 값과 같아야 한다.
    expect(screen.getByRole("link", { name: "퀴즈 풀어보기" })).toHaveAttribute(
      "href",
      "/my-lectures/s1/quiz",
    );
  });

  it("예상 시간이 없으면 문항 수만 적는다 — '약 0분' 을 쓰지 않는다", async () => {
    renderReport({
      quizRequest: async () => quizWith(4, null),
    });

    expect(await screen.findByText("총 4문제")).toBeInTheDocument();
  });

  it("퀴즈가 없으면 풀기 버튼을 내지 않는다 — 눌러도 빈 화면뿐이다", async () => {
    renderReport({
      quizRequest: async () => {
        throw new StudentQuizError("missing", 404);
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
