import { render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

vi.mock("@/domains/auth", () => ({ useAuth: () => ({ accessToken: "token" }) }));

import { StudentClipError } from "../infrastructure/studentClipApi";
import { InstructorReportClip } from "./InstructorReportClip";

const envelope = (data: unknown) => ({ isSuccess: true, code: "COMMON200", message: "ok", data });

const clip = {
  recordingUrl: "https://media.example/lecture.mp4?token=a",
  durationSeconds: 5430,
  transcript: [
    { startSeconds: 2, endSeconds: 30, speakerName: "박서준", text: "오늘은 상태 관리를 다룹니다." },
  ],
  seekTimestamp: 0,
};

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("InstructorReportClip", () => {
  it("기본 requester 는 강사 수업 클립 경로를 부른다 — 학생 경로는 강사에게 403 이다", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => envelope(clip) }),
    );

    render(<InstructorReportClip sessionId="s7" title="React" />);

    expect(await screen.findByTestId("report-video")).toBeInTheDocument();
    expect(screen.getByText("박서준")).toBeInTheDocument();
    const [url] = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(String(url)).toContain("/api/v1/sessions/s7/reports/instructor/clip");
  });

  it("404 는 준비 전 안내다 — 공통 리포트가 게시되기 전이다", async () => {
    render(
      <InstructorReportClip
        sessionId="s7"
        title="t"
        request={async () => {
          throw new StudentClipError("boom", 404);
        }}
      />,
    );

    expect(await screen.findByText("아직 분석이 끝나지 않았어요")).toBeInTheDocument();
  });
});
