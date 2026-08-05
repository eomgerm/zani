import { fireEvent, render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

const auth = vi.hoisted(() => ({ accessToken: "token" as string | null }));
vi.mock("@/domains/auth", () => ({ useAuth: () => auth }));

const role = vi.hoisted(() => ({
  status: "ready" as "loading" | "ready" | "unknown",
  role: "STUDENT" as "INSTRUCTOR" | "STUDENT" | null,
  lecture: null as { id: string; title: string } | null,
}));
vi.mock("./useSessionRole", () => ({ useSessionRole: () => role }));

import { QuizScreen } from "./QuizScreen";
// report 도메인은 공개 API 로만 가져온다. infrastructure 를 직접 열면 경계가 무의미해진다.
import { StudentQuizError, type StudentQuiz } from "@/domains/report";

/** 실제 세션 id. fixture 목록에는 없는 값이라 fixture 폴백이 있으면 링크가 엉뚱해진다. */
const SESSION_ID = "1000000002001";
const REPORT_HREF = `/my-lectures/${SESSION_ID}/report?tab=report`;

const question = (n: number, grading: StudentQuiz["questions"][number]["grading"] = null) => ({
  questionId: `q${n}`,
  order: n,
  text: `문항 ${n} 본문`,
  options: [
    { optionId: `q${n}o1`, order: 1, text: `${n}번 보기 하나` },
    { optionId: `q${n}o2`, order: 2, text: `${n}번 보기 둘` },
  ],
  grading,
});

const quizWith = (overrides: Partial<StudentQuiz> = {}): StudentQuiz => ({
  title: "React 상태관리 심화",
  description: "설명",
  estimatedDurationMinutes: 3,
  submitted: false,
  questions: [question(1), question(2)],
  ...overrides,
});

const renderQuiz = (overrides: Partial<Parameters<typeof QuizScreen>[0]> = {}) =>
  render(
    <QuizScreen
      lectureId={SESSION_ID}
      request={async () => quizWith()}
      submitRequest={async () => ({
        totalCount: 2,
        correctCount: 1,
        gradingByQuestionId: {
          q1: {
            correct: true,
            selectedOptionId: "q1o1",
            correctOptionId: "q1o1",
            explanation: "1번 해설",
            sectionStartSeconds: 1450,
          },
          q2: {
            correct: false,
            selectedOptionId: "q2o1",
            correctOptionId: "q2o2",
            explanation: "2번 해설",
            sectionStartSeconds: null,
          },
        },
      })}
      {...overrides}
    />,
  );

/** 문항을 순서대로 하나씩 골라 마지막에 제출한다. */
const answerAllAndSubmit = async () => {
  fireEvent.click(await screen.findByRole("button", { name: "1번 보기 하나" }));
  fireEvent.click(screen.getByRole("button", { name: "다음 문제 →" }));
  fireEvent.click(await screen.findByRole("button", { name: "2번 보기 하나" }));
  fireEvent.click(screen.getByRole("button", { name: "제출하기" }));
};

beforeEach(() => {
  auth.accessToken = "token";
  role.status = "ready";
  role.role = "STUDENT";
  role.lecture = { id: SESSION_ID, title: "React 상태관리 심화" };
});

describe("QuizScreen", () => {
  it("제목을 세션 응답에서 읽고 문항을 하나씩 보여준다", async () => {
    renderQuiz();

    expect(await screen.findByText("React 상태관리 심화 · AI 이해도 퀴즈")).toBeInTheDocument();
    expect(screen.getByText("문항 1 본문")).toBeInTheDocument();
    // 한 번에 한 문항만 보인다.
    expect(screen.queryByText("문항 2 본문")).not.toBeInTheDocument();
    expect(screen.getByText("1 / 2")).toBeInTheDocument();
  });

  it("좌상단 뒤로가기가 이 수업의 리포트 탭을 가리킨다", async () => {
    renderQuiz();

    expect((await screen.findAllByRole("link"))[0]).toHaveAttribute("href", REPORT_HREF);
  });

  it("제출 전에는 정답도 해설도 보여주지 않는다", async () => {
    renderQuiz();

    fireEvent.click(await screen.findByRole("button", { name: "1번 보기 하나" }));

    expect(screen.queryByText("1번 해설")).not.toBeInTheDocument();
    expect(screen.queryByText(/정답/)).not.toBeInTheDocument();
  });

  it("문항 사이를 오가며 답을 고쳐 쓸 수 있다 — 제출 전에는 확정이 아니다", async () => {
    renderQuiz();

    fireEvent.click(await screen.findByRole("button", { name: "1번 보기 하나" }));
    fireEvent.click(screen.getByRole("button", { name: "다음 문제 →" }));
    fireEvent.click(screen.getByRole("button", { name: "이전" }));

    // 돌아와도 고른 보기가 남아 있고, 다른 보기로 바꿀 수 있다.
    expect(screen.getByRole("button", { name: "1번 보기 하나" })).toHaveAttribute(
      "aria-pressed",
      "true",
    );
    fireEvent.click(screen.getByRole("button", { name: "1번 보기 둘" }));
    expect(screen.getByRole("button", { name: "1번 보기 둘" })).toHaveAttribute(
      "aria-pressed",
      "true",
    );
  });

  it("모든 문항을 채우기 전에는 제출할 수 없다", async () => {
    renderQuiz();

    fireEvent.click(await screen.findByRole("button", { name: "다음 문제 →" }));

    const submit = screen.getByRole("button", { name: /남았어요/ });
    expect(submit).toBeDisabled();
  });

  it("제출하면 점수와 문항별 정답·해설을 보여준다", async () => {
    renderQuiz();

    await answerAllAndSubmit();

    expect(await screen.findByText("2개 개념 중 1개를 확인했어요.")).toBeInTheDocument();
    expect(screen.getByText("다시 살펴볼 개념이 1개 있어요.")).toBeInTheDocument();
    expect(screen.getByText("1번 해설")).toBeInTheDocument();
    expect(screen.getByText("2번 해설")).toBeInTheDocument();
    // 정답 보기 본문을 id 로 되찾아 보여준다.
    expect(screen.getByText(/1번 보기 하나/)).toBeInTheDocument();
  });

  it("구간 시각을 아는 문항만 다시 보기 링크를 낸다", async () => {
    renderQuiz();

    await answerAllAndSubmit();
    // 헤더의 뒤로가기 링크는 처음부터 있다. 결과 화면이 뜬 뒤에 세어야 한다.
    await screen.findByText("2개 개념 중 1개를 확인했어요.");

    const seekLinks = screen
      .getAllByRole("link")
      .filter((link) => link.textContent?.includes("관련 강의 구간 다시 보기") === true);
    // q1 만 sectionStartSeconds 가 있다. q2 는 링크가 없다.
    expect(seekLinks).toHaveLength(1);
    expect(seekLinks[0]).toHaveAttribute(
      "href",
      `/my-lectures/${SESSION_ID}/report?tab=clip&seek=1450`,
    );
  });

  it("결과 화면에서도 리포트 탭으로 돌아간다", async () => {
    renderQuiz();

    await answerAllAndSubmit();

    expect(await screen.findByRole("link", { name: "학습 리포트로 돌아가기" })).toHaveAttribute(
      "href",
      REPORT_HREF,
    );
  });

  it("이미 제출한 퀴즈로 들어오면 저장된 채점을 바로 보여주고 다시 풀 길을 두지 않는다", async () => {
    renderQuiz({
      request: async () =>
        quizWith({
          submitted: true,
          questions: [
            question(1, {
              correct: true,
              selectedOptionId: "q1o1",
              correctOptionId: "q1o1",
              explanation: "저장된 해설",
              sectionStartSeconds: null,
            }),
          ],
        }),
    });

    expect(await screen.findByText("저장된 해설")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "다시 풀기" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "제출하기" })).not.toBeInTheDocument();
  });

  it.each([
    [404, "아직 퀴즈가 준비되지 않았어요"],
    [403, "이 수업의 퀴즈를 볼 수 없어요"],
  ])("조회 %s 는 상태에 맞는 안내를 낸다", async (status, message) => {
    renderQuiz({
      request: async () => {
        throw new StudentQuizError("no", status);
      },
    });

    expect(await screen.findByText(message)).toBeInTheDocument();
  });

  it("조회는 끝났는데 문항이 없으면 준비 전이라고 말한다 — 영원히 불러오지 않는다", async () => {
    renderQuiz({ request: async () => quizWith({ questions: [] }) });

    expect(await screen.findByText("아직 퀴즈가 준비되지 않았어요")).toBeInTheDocument();
    expect(screen.queryByText("퀴즈를 불러오는 중이에요")).not.toBeInTheDocument();
  });

  it("제출이 실패하면 이유를 알리고 답안을 그대로 둔다", async () => {
    renderQuiz({
      submitRequest: async () => {
        throw new StudentQuizError("boom", 500);
      },
    });

    await answerAllAndSubmit();

    expect(await screen.findByText(/답안을 제출하지 못했어요/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "2번 보기 하나" })).toHaveAttribute(
      "aria-pressed",
      "true",
    );
  });
});
