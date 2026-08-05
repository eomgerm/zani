import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { QuizScreen } from "./QuizScreen";
import { quizData } from "./fixtures";

/** 실제 세션 id. fixture 목록에는 없는 값이라 fixture 폴백이 있으면 링크가 엉뚱해진다. */
const SESSION_ID = "1000000002001";

/** 결과 화면까지 진행한다 — 문항마다 보기 하나 고르고 제출 → 다음. */
const finishQuiz = () => {
  quizData.forEach((question, index) => {
    fireEvent.click(screen.getByRole("button", { name: question.opts[0] }));
    fireEvent.click(screen.getByRole("button", { name: "답안 제출" }));
    fireEvent.click(
      screen.getByRole("button", {
        name: index + 1 >= quizData.length ? "결과 보기" : "다음 문제 →",
      }),
    );
  });
};

describe("QuizScreen", () => {
  it("좌상단 뒤로가기가 이 수업의 리포트를 가리킨다", () => {
    render(<QuizScreen lectureId={SESSION_ID} />);

    // fixture 폴백을 쓰면 /my-lectures/s1/report 로 새어 "리포트를 볼 수 없어요" 로 끝난다.
    expect(screen.getAllByRole("link")[0]).toHaveAttribute(
      "href",
      `/my-lectures/${SESSION_ID}/report`,
    );
  });

  it("결과 화면의 돌아가기도 같은 리포트를 가리킨다", () => {
    render(<QuizScreen lectureId={SESSION_ID} />);

    finishQuiz();

    expect(screen.getByRole("link", { name: "학습 리포트로 돌아가기" })).toHaveAttribute(
      "href",
      `/my-lectures/${SESSION_ID}/report`,
    );
  });

  it("fixture 에 있는 id 로 들어와도 URL 의 id 를 그대로 쓴다", () => {
    render(<QuizScreen lectureId="s1" />);

    expect(screen.getAllByRole("link")[0]).toHaveAttribute("href", "/my-lectures/s1/report");
  });
});
