import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

vi.mock("@/domains/auth", () => ({ useAuth: () => ({ accessToken: "token" }) }));

import { SessionSummaryError } from "../infrastructure/sessionSummaryApi";
import { SessionSummaryCard } from "./SessionSummaryCard";

const SUMMARY = "이번 수업은 지역 상태에서 출발해 Context 리렌더링으로 이어졌습니다.";

const SECTIONS = [
  {
    startOffsetMs: 0,
    startSeconds: 0,
    endSeconds: 600,
    title: "상태 관리 개요",
    summary: "지역 상태와 전역 상태를 가르는 기준을 설명했다.",
  },
  {
    startOffsetMs: 600_000,
    startSeconds: 600,
    endSeconds: 1200,
    title: "Context 리렌더링",
    summary: "Context 값이 바뀔 때 어디까지 다시 그리는지 짚었다.",
  },
] as const;

const failWith = (status: number) => async () => {
  throw new SessionSummaryError("boom", status);
};

describe("SessionSummaryCard", () => {
  it("서버가 준 요약 문단을 그린다", async () => {
    render(<SessionSummaryCard sessionId="s1" request={async () => ({ summary: SUMMARY, sections: [] })} />);

    expect(await screen.findByText(SUMMARY)).toBeInTheDocument();
  });

  it("구간을 시각·제목·요약으로 편다", async () => {
    render(
      <SessionSummaryCard
        sessionId="s1"
        request={async () => ({ summary: SUMMARY, sections: [...SECTIONS] })}
      />,
    );

    expect(await screen.findByText("상태 관리 개요")).toBeInTheDocument();
    expect(screen.getByText("00:00–10:00")).toBeInTheDocument();
    expect(screen.getByText(SECTIONS[0].summary)).toBeInTheDocument();
    expect(screen.getByText("Context 리렌더링")).toBeInTheDocument();
    // 전체 문단은 구간이 있어도 그대로 남는다. 구간은 그 아래에 편다.
    expect(screen.getByText(SUMMARY)).toBeInTheDocument();
  });

  it("구간 시각을 누르면 그 구간 시작 시각으로 이동을 청한다", async () => {
    const onSeek = vi.fn();
    render(
      <SessionSummaryCard
        sessionId="s1"
        request={async () => ({ summary: SUMMARY, sections: [...SECTIONS] })}
        onSeek={onSeek}
      />,
    );

    fireEvent.click(await screen.findByRole("button", { name: /Context 리렌더링 구간 재생/ }));

    // 끝 시각이 아니라 시작 시각이다. 구간을 다시 보려면 그 앞머리부터 들어야 한다.
    expect(onSeek).toHaveBeenCalledWith(600);
  });

  it("이동 배선이 없으면 시각을 버튼으로 내지 않는다", async () => {
    render(
      <SessionSummaryCard
        sessionId="s1"
        request={async () => ({ summary: SUMMARY, sections: [...SECTIONS] })}
      />,
    );

    expect(await screen.findByText("00:00–10:00")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /구간 재생/ })).not.toBeInTheDocument();
  });

  it("구간이 없으면 문단만 그린다 — 빈 목록은 오류가 아니다", async () => {
    render(<SessionSummaryCard sessionId="s1" request={async () => ({ summary: SUMMARY, sections: [] })} />);

    expect(await screen.findByText(SUMMARY)).toBeInTheDocument();
    expect(screen.queryByRole("list")).not.toBeInTheDocument();
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
      return { summary: SUMMARY, sections: [] };
    };

    render(<SessionSummaryCard sessionId="s1" request={request} />);

    fireEvent.click(await screen.findByRole("button", { name: "다시 시도" }));

    expect(await screen.findByText(SUMMARY)).toBeInTheDocument();
  });

  it("역할 인자를 받지 않는다 — 강사·학생이 같은 문장을 본다", async () => {
    const request = async () => ({ summary: SUMMARY, sections: [] });

    const asStudent = render(<SessionSummaryCard sessionId="s1" request={request} />);
    const studentText = (await asStudent.findByText(SUMMARY)).textContent;
    asStudent.unmount();

    const asInstructor = render(<SessionSummaryCard sessionId="s1" request={request} />);
    const instructorText = (await asInstructor.findByText(SUMMARY)).textContent;

    expect(studentText).toBe(instructorText);
  });
});
