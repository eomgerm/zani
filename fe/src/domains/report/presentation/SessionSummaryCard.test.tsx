import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

vi.mock("@/domains/auth", () => ({ useAuth: () => ({ accessToken: "token" }) }));

import { SessionSummaryError } from "../infrastructure/sessionSummaryApi";
import { SessionSummaryCard } from "./SessionSummaryCard";

const SUMMARY = "이번 수업은 지역 상태에서 출발해 Context 리렌더링으로 이어졌습니다.";

const failWith = (status: number) => async () => {
  throw new SessionSummaryError("boom", status);
};

describe("SessionSummaryCard", () => {
  it("서버가 준 요약 문단을 그린다", async () => {
    render(<SessionSummaryCard sessionId="s1" request={async () => ({ summary: SUMMARY })} />);

    expect(await screen.findByText(SUMMARY)).toBeInTheDocument();
  });

  it("카드 제목은 어떤 상태에서도 남는다 — 사라지면 아래 내용이 밀려 올라간다", async () => {
    render(<SessionSummaryCard sessionId="s1" request={failWith(404)} />);

    expect(await screen.findByText("아직 분석이 끝나지 않았어요")).toBeInTheDocument();
    expect(screen.getByText("수업 요약 레포트")).toBeInTheDocument();
  });

  it("404는 '아직 준비 전'으로 읽는다", async () => {
    render(<SessionSummaryCard sessionId="s1" request={failWith(404)} />);

    expect(await screen.findByText("아직 분석이 끝나지 않았어요")).toBeInTheDocument();
  });

  it("진행 중 세션(409)도 '아직 준비 전'으로 흡수한다", async () => {
    render(<SessionSummaryCard sessionId="s1" request={failWith(409)} />);

    expect(await screen.findByText("아직 분석이 끝나지 않았어요")).toBeInTheDocument();
  });

  it("403은 권한 문제로 따로 말한다", async () => {
    render(<SessionSummaryCard sessionId="s1" request={failWith(403)} />);

    expect(await screen.findByText("이 수업의 요약을 볼 수 없어요")).toBeInTheDocument();
  });

  it("실패하면 다시 시도로 재조회한다", async () => {
    let calls = 0;
    const request = async () => {
      calls += 1;
      if (calls === 1) throw new SessionSummaryError("boom", 500);
      return { summary: SUMMARY };
    };

    render(<SessionSummaryCard sessionId="s1" request={request} />);

    fireEvent.click(await screen.findByRole("button", { name: "다시 시도" }));

    expect(await screen.findByText(SUMMARY)).toBeInTheDocument();
  });

  it("역할 인자를 받지 않는다 — 강사·학생이 같은 문장을 본다", async () => {
    const request = async () => ({ summary: SUMMARY });

    const asStudent = render(<SessionSummaryCard sessionId="s1" request={request} />);
    const studentText = (await asStudent.findByText(SUMMARY)).textContent;
    asStudent.unmount();

    const asInstructor = render(<SessionSummaryCard sessionId="s1" request={request} />);
    const instructorText = (await asInstructor.findByText(SUMMARY)).textContent;

    expect(studentText).toBe(instructorText);
  });
});
