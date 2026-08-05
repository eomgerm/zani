import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

vi.mock("@/domains/auth", () => ({ useAuth: () => ({ accessToken: "token" }) }));

import { StudentClipError, type StudentClip } from "../infrastructure/studentClipApi";
import { StudentReportClip } from "./StudentReportClip";

const clipWith = (overrides: Partial<StudentClip> = {}): StudentClip => ({
  recordingUrl: "https://media.example/lecture.mp4?token=a",
  durationSeconds: 5430,
  transcript: [
    { startSeconds: 2, endSeconds: 30, speakerName: "박서준", text: "오늘은 상태 관리를 다룹니다." },
    { startSeconds: 125, endSeconds: 150, speakerName: "정하윤", text: "Context 는 언제 쓰나요?" },
  ],
  seekTimestamp: 0,
  ...overrides,
});

const failWith = (status: number) => async () => {
  throw new StudentClipError("boom", status);
};

describe("StudentReportClip", () => {
  it("녹화 플레이어와 실명 화자 전사를 함께 그린다", async () => {
    render(
      <StudentReportClip sessionId="s1" title="React" request={async () => clipWith()} />,
    );

    expect(await screen.findByTestId("report-video")).toBeInTheDocument();
    expect(screen.getByText("박서준")).toBeInTheDocument();
    expect(screen.getByText(/Context 는 언제 쓰나요/)).toBeInTheDocument();
  });

  it("전사 행을 누르면 플레이어가 그 발화 시각으로 이동한다", async () => {
    render(
      <StudentReportClip sessionId="s1" title="React" request={async () => clipWith()} />,
    );

    const video = (await screen.findByTestId("report-video")) as HTMLVideoElement;
    Object.defineProperty(video, "readyState", { value: 1, configurable: true });

    fireEvent.click(screen.getByRole("button", { name: /Context 는 언제 쓰나요/ }));

    expect(video.currentTime).toBe(125);
  });

  it("서버가 준 초기 위치에서 시작한다", async () => {
    render(
      <StudentReportClip
        sessionId="s1"
        title="React"
        request={async () => clipWith({ seekTimestamp: 1440 })}
      />,
    );

    const video = (await screen.findByTestId("report-video")) as HTMLVideoElement;
    Object.defineProperty(video, "duration", { value: 5430, configurable: true });
    fireEvent.loadedMetadata(video);

    expect(video.currentTime).toBe(1440);
  });

  it("403 은 권한 안내, 404 는 준비 전 안내다", async () => {
    const forbidden = render(
      <StudentReportClip sessionId="s1" title="t" request={failWith(403)} />,
    );
    expect(await screen.findByText("이 수업의 다시 보기를 볼 수 없어요")).toBeInTheDocument();
    forbidden.unmount();

    render(<StudentReportClip sessionId="s1" title="t" request={failWith(404)} />);
    expect(await screen.findByText("아직 분석이 끝나지 않았어요")).toBeInTheDocument();
  });

  it("그 밖의 실패는 다시 시도 버튼을 준다", async () => {
    render(<StudentReportClip sessionId="s1" title="t" request={failWith(500)} />);

    expect(await screen.findByText("다시 보기를 불러오지 못했어요")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "다시 시도" })).toBeInTheDocument();
  });
});
