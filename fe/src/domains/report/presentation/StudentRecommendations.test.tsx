import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

vi.mock("@/domains/auth", () => ({ useAuth: () => ({ accessToken: "token" }) }));

import { StudentReportError, type StudentReport } from "../infrastructure/studentReportApi";
import { StudentRecommendations } from "./StudentRecommendations";

const reportWith = (overrides: Partial<StudentReport> = {}): StudentReport => ({
  recordingUrl: null,
  durationSeconds: 5430,
  transcript: [],
  recommendations: [
    {
      id: "1",
      title: "useMemo 메모이제이션 패턴",
      reason: "헷갈림 응답과 반복된 확인 필요가 함께 근거가 됐어요.",
      startSeconds: 1440,
      endSeconds: 1859,
      recommendationType: "CONFUSED",
    },
    {
      id: "2",
      title: "상태관리 라이브러리 비교",
      reason: "직접 남긴 질문이 이 구간을 가리켜요.",
      startSeconds: 1860,
      endSeconds: 2400,
      recommendationType: "QUESTION",
    },
  ],
  seekTimestamp: 0,
  ...overrides,
});

describe("StudentRecommendations", () => {
  it("추천의 구간 시각·제목·이유를 함께 그린다 (REPORT-S-003)", async () => {
    render(
      <StudentRecommendations
        sessionId="s1"
        onSeekToClip={() => {}}
        request={async () => reportWith()}
      />,
    );

    expect(await screen.findByText("useMemo 메모이제이션 패턴")).toBeInTheDocument();
    expect(screen.getByText(/반복된 확인 필요가 함께 근거/)).toBeInTheDocument();
    // 1440초 → 24:00
    expect(screen.getByText("24:00")).toBeInTheDocument();
    expect(screen.getByText("헷갈림")).toBeInTheDocument();
  });

  it("추천을 누르면 그 구간 시각으로 이동을 요청한다 (REPORT-S-004)", async () => {
    const onSeekToClip = vi.fn();
    render(
      <StudentRecommendations
        sessionId="s1"
        onSeekToClip={onSeekToClip}
        request={async () => reportWith()}
      />,
    );

    fireEvent.click(await screen.findByRole("button", { name: /useMemo 메모이제이션/ }));

    expect(onSeekToClip).toHaveBeenCalledWith(1440);
  });

  it("추천 0개는 성공 상태다 — 근거 없음을 명확히 말한다 (REPORT-S-005)", async () => {
    render(
      <StudentRecommendations
        sessionId="s1"
        onSeekToClip={() => {}}
        request={async () => reportWith({ recommendations: [] })}
      />,
    );

    expect(await screen.findByText("추천할 구간이 없어요")).toBeInTheDocument();
  });

  it("모르는 근거 유형은 기본 라벨로 그린다 — 추천 자체는 버리지 않는다", async () => {
    render(
      <StudentRecommendations
        sessionId="s1"
        onSeekToClip={() => {}}
        request={async () =>
          reportWith({
            recommendations: [
              {
                id: "9",
                title: "예외 처리 흐름",
                reason: "근거",
                startSeconds: 4325,
                endSeconds: 4400,
                recommendationType: "SOMETHING_NEW",
              },
            ],
          })
        }
      />,
    );

    expect(await screen.findByText("복습 추천")).toBeInTheDocument();
    expect(screen.getByText("예외 처리 흐름")).toBeInTheDocument();
  });

  it("403 이면 본인 것만 볼 수 있다고 알린다", async () => {
    render(
      <StudentRecommendations
        sessionId="s1"
        onSeekToClip={() => {}}
        request={async () => {
          throw new StudentReportError("forbidden", 403);
        }}
      />,
    );

    expect(await screen.findByText("복습 추천은 본인 것만 볼 수 있어요")).toBeInTheDocument();
  });
});
