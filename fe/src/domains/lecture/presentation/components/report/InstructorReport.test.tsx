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

import { InstructorReport } from "./InstructorReport";
import { InstructorReportError } from "@/domains/report";
import type {
  GroupTimelineRequester,
  InstructorReport as InstructorReportData,
  InstructorReportRequester,
} from "@/domains/report";

const reportWith = (overrides: Partial<InstructorReportData> = {}): InstructorReportData => ({
  overallFeedback: "전반적으로 흐름이 좋았습니다.",
  stats: { studentCount: 32, durationSeconds: 7500, questionCount: 184, alertCount: 7 },
  scores: [
    { evaluationType: "DELIVERY", score: 88 },
    { evaluationType: "STRUCTURE_FLOW", score: 84 },
  ],
  insights: [],
  ...overrides,
});

/** 집중 흐름 응답. 네 구간 중 셋이 2.5 이상이라 비율은 75% 다. */
const attentionWith = (focusLevels: (number | null)[] = [3.2, 3.0, 2.6, 1.4]) => ({
  durationSeconds: 120,
  focusFlow: {
    intervalSeconds: 30,
    points: focusLevels.map((focusLevel, index) => ({
      offsetSeconds: index * 30,
      focusLevel,
      eligibleCount: 28,
    })),
  },
  signals: { intervalSeconds: 5, points: [] },
  distractedIntervals: [],
  sections: [],
});

const renderReport = (options: {
  request: InstructorReportRequester;
  attentionRequest?: GroupTimelineRequester;
  onJumpToClip?: (seconds: number) => void;
}) =>
  render(
    <InstructorReport
      sessionId="9200001"
      onJumpToClip={options.onJumpToClip ?? (() => {})}
      request={options.request}
      attentionRequest={options.attentionRequest ?? (async () => attentionWith())}
    />,
  );

const reportError = (status: number) => new InstructorReportError(`status ${status}`, status);

describe("InstructorReport 한눈에 보기", () => {
  it("집계를 서버 값으로 채운다", async () => {
    renderReport({ request: async () => reportWith() });

    expect(await screen.findByText("32명")).toBeInTheDocument();
    expect(screen.getByText("2시간 5분")).toBeInTheDocument();
    expect(screen.getByText("184개")).toBeInTheDocument();
    expect(screen.getByText("7회")).toBeInTheDocument();
  });

  it("집중 구간 비율은 집중 흐름 점들에서 세고 평가를 붙인다", async () => {
    renderReport({ request: async () => reportWith() });

    // 리포트 응답에 없는 값이다. 넷 중 셋이 2.5 이상이므로 75%.
    expect(await screen.findByText("75%")).toBeInTheDocument();
    expect(screen.getByText("보통")).toBeInTheDocument();
  });

  it("질문 수를 셀 수 없으면 0 개가 아니라 비운다", async () => {
    renderReport({
      request: async () =>
        reportWith({
          stats: { studentCount: 32, durationSeconds: 7500, questionCount: null, alertCount: 7 },
        }),
    });

    await screen.findByText("32명");
    // "아무도 질문하지 않았다" 로 읽히면 안 된다.
    expect(screen.queryByText("0개")).not.toBeInTheDocument();
  });

  it("종료 시각이 없던 과거 세션은 0분 대신 비운다", async () => {
    renderReport({
      request: async () =>
        reportWith({
          stats: { studentCount: 3, durationSeconds: 0, questionCount: 0, alertCount: 0 },
        }),
    });

    await screen.findByText("3명");
    expect(screen.queryByText("0분")).not.toBeInTheDocument();
  });

  it("집중 흐름을 못 받아도 나머지 넷은 그린다", async () => {
    renderReport({
      request: async () => reportWith(),
      attentionRequest: async () => {
        throw new Error("down");
      },
    });

    // 엔드포인트가 서로 다르다. 한쪽이 죽었다고 다른 쪽까지 감추지 않는다.
    expect(await screen.findByText("32명")).toBeInTheDocument();
    expect(screen.queryByText(/%$/)).not.toBeInTheDocument();
  });
});

describe("InstructorReport AI 수업 피드백", () => {
  it("종합 피드백과 분야 이름을 서버 값으로 그린다", async () => {
    renderReport({ request: async () => reportWith() });

    expect(await screen.findByText("전반적으로 흐름이 좋았습니다.")).toBeInTheDocument();
    expect(screen.getByText("전달력")).toBeInTheDocument();
    expect(screen.getByText("수업 구성")).toBeInTheDocument();
  });

  it("모르는 평가 분야도 버리지 않고 점수를 보여 준다", async () => {
    renderReport({
      request: async () => reportWith({ scores: [{ evaluationType: "PACING", score: 62 }] }),
    });

    // AI 가 쓰는 분류가 늘어도 화면이 값을 삼키면 안 된다.
    expect(await screen.findByText("PACING")).toBeInTheDocument();
  });

  it("한 장에 제목·관찰·해 볼 것을 함께 그린다", async () => {
    renderReport({
      request: async () =>
        reportWith({
          insights: [
            {
              title: "어려운 구간 보강",
              content: "예외 처리 구간에서 집중도가 낮았어요.",
              suggestion: "추가 예시 코드와 실습 시간을 늘려보세요.",
              startSeconds: 4800,
              endSeconds: 6000,
            },
          ],
        }),
    });

    // 296 시안의 카드 모양이다. 서버가 셋을 한 행으로 줘서 화면이 짝을 지을 일이 없다.
    expect(await screen.findByText("어려운 구간 보강")).toBeInTheDocument();
    expect(screen.getByText("예외 처리 구간에서 집중도가 낮았어요.")).toBeInTheDocument();
    expect(screen.getByText("추가 예시 코드와 실습 시간을 늘려보세요.")).toBeInTheDocument();
  });

  it("제안이 없는 인사이트는 TIP 줄 없이 그린다", async () => {
    renderReport({
      request: async () =>
        reportWith({
          insights: [
            {
              title: "어려운 구간 보강",
              content: "예외 처리 구간에서 집중도가 낮았어요.",
              suggestion: "",
              startSeconds: 4800,
              endSeconds: null,
            },
          ],
        }),
    });

    await screen.findByText("어려운 구간 보강");
    // 빈 TIP 딱지만 남으면 제안이 있었는데 잃어버린 것처럼 보인다.
    expect(screen.queryByText("TIP.")).not.toBeInTheDocument();
  });

  it("구간이 있는 인사이트를 누르면 그 시각으로 옮긴다", async () => {
    const onJumpToClip = vi.fn();
    renderReport({
      onJumpToClip,
      request: async () =>
        reportWith({
          insights: [
            {
              title: "어려운 구간 보강",
              content: "예외 처리 구간에서 집중도가 낮았어요.",
              suggestion: "",
              startSeconds: 4800,
              endSeconds: 6000,
            },
          ],
        }),
    });

    fireEvent.click(await screen.findByRole("button", { name: /어려운 구간 보강/ }));

    expect(onJumpToClip).toHaveBeenCalledWith(4800);
  });

  it("수업 전체를 가리키는 인사이트는 누를 수 없다", async () => {
    renderReport({
      request: async () =>
        reportWith({
          insights: [
            {
              title: "전체 흐름",
              content: "후반부로 갈수록 회복됐어요.",
              suggestion: "",
              startSeconds: null,
              endSeconds: null,
            },
          ],
        }),
    });

    await screen.findByText("후반부로 갈수록 회복됐어요.");
    // 갈 곳이 없는 카드에 눌리는 버튼을 두면 아무 일도 안 일어나는 클릭이 생긴다.
    expect(screen.queryByRole("button", { name: /전체 흐름/ })).not.toBeInTheDocument();
  });

  it("인사이트가 없으면 빈 자리를 설명한다", async () => {
    renderReport({ request: async () => reportWith() });

    expect(await screen.findByText("수업 인사이트가 아직 없어요.")).toBeInTheDocument();
  });
});

describe("InstructorReport 누락 상태", () => {
  it("권한이 없으면 미생성과 다른 말을 한다", async () => {
    renderReport({ request: async () => Promise.reject(reportError(403)) });

    expect(await screen.findByText("이 수업의 리포트를 볼 권한이 없어요")).toBeInTheDocument();
  });

  it("아직 만들어지지 않았으면 기다리라고 한다", async () => {
    renderReport({ request: async () => Promise.reject(reportError(404)) });

    expect(await screen.findByText("아직 리포트가 만들어지지 않았어요")).toBeInTheDocument();
  });

  it("실패하면 다시 시도할 수 있다", async () => {
    const request = vi
      .fn()
      .mockRejectedValueOnce(reportError(500))
      .mockResolvedValueOnce(reportWith()) as unknown as InstructorReportRequester;

    renderReport({ request });

    fireEvent.click(await screen.findByRole("button", { name: "다시 시도" }));

    expect(await screen.findByText("32명")).toBeInTheDocument();
  });

  it("리포트를 못 받아도 집중 흐름 카드는 그린다", async () => {
    renderReport({ request: async () => Promise.reject(reportError(404)) });

    await screen.findByText("아직 리포트가 만들어지지 않았어요");
    // 다른 엔드포인트다. 리포트가 없다고 이미 받아 둔 흐름까지 감출 이유가 없다.
    expect(screen.getByText("집중 흐름")).toBeInTheDocument();
  });

  it("리포트가 없으면 AI 수업 피드백 블록을 아예 그리지 않는다", async () => {
    renderReport({ request: async () => Promise.reject(reportError(403)) });

    await screen.findByText("이 수업의 리포트를 볼 권한이 없어요");
    // 빈 제목만 남으면 내용을 잃어버린 것처럼 보인다.
    expect(screen.queryByText("AI 수업 피드백")).not.toBeInTheDocument();
  });
});
