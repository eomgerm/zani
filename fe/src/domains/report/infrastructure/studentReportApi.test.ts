import { afterEach, describe, expect, it, vi } from "vitest";

import { requestStudentReport, StudentReportError } from "./studentReportApi";

const respondWith = (body: unknown, status = 200) => {
  vi.stubGlobal(
    "fetch",
    vi.fn().mockResolvedValue({
      ok: status >= 200 && status < 300,
      status,
      json: async () => body,
    }),
  );
};

const envelope = (data: unknown) => ({ isSuccess: true, code: "COMMON200", message: "ok", data });

const reportWith = (overrides: Record<string, unknown> = {}) => ({
  recordingUrl: "https://media.example/lecture.mp4?token=abc",
  durationSeconds: 5430,
  transcript: [
    { startSeconds: 2, endSeconds: 32, speakerName: "박서준", text: "오늘은 상태 관리를 다룹니다." },
  ],
  recommendations: [],
  seekTimestamp: 0,
  ...overrides,
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("requestStudentReport", () => {
  it("학생 리포트 경로를 부르고 Bearer 토큰을 보낸다", async () => {
    respondWith(envelope(reportWith()));

    await requestStudentReport("s1", "token");

    const [url, init] = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(String(url)).toContain("/api/v1/sessions/s1/reports/student");
    expect((init as RequestInit).headers).toMatchObject({ Authorization: "Bearer token" });
  });

  it("녹화 URL·전사·초기 위치를 그대로 읽는다", async () => {
    respondWith(envelope(reportWith({ seekTimestamp: 1440 })));

    const report = await requestStudentReport("s1", "token");

    expect(report.recordingUrl).toBe("https://media.example/lecture.mp4?token=abc");
    expect(report.durationSeconds).toBe(5430);
    expect(report.seekTimestamp).toBe(1440);
    expect(report.transcript[0]).toEqual({
      startSeconds: 2,
      endSeconds: 32,
      speakerName: "박서준",
      text: "오늘은 상태 관리를 다룹니다.",
    });
  });

  it("녹화 URL 이 없으면 null 이다 — 빈 문자열을 src 로 흘리지 않는다", async () => {
    respondWith(envelope(reportWith({ recordingUrl: "" })));

    const report = await requestStudentReport("s1", "token");

    expect(report.recordingUrl).toBeNull();
  });

  it("깨진 전사 행만 버린다 — 전사 전체를 버리지 않는다", async () => {
    respondWith(
      envelope(
        reportWith({
          transcript: [
            { startSeconds: 2, endSeconds: 10, speakerName: "박서준", text: "정상 행" },
            { endSeconds: 20, speakerName: "박서준", text: "시작 시각 없음" },
            { startSeconds: 30, endSeconds: 40, speakerName: "박서준", text: "" },
            { startSeconds: 50, speakerName: 7, text: "화자 이름이 깨짐" },
          ],
        }),
      ),
    );

    const report = await requestStudentReport("s1", "token");

    expect(report.transcript).toHaveLength(2);
    expect(report.transcript[0].text).toBe("정상 행");
    // 화자 이름이 깨져도 본문은 살린다. 이름만 비운다.
    expect(report.transcript[1]).toMatchObject({ speakerName: "", text: "화자 이름이 깨짐" });
  });

  it("전사가 뒤섞여 와도 시작 시각 순으로 정렬한다 — 커서 계산이 정렬을 전제한다", async () => {
    respondWith(
      envelope(
        reportWith({
          transcript: [
            { startSeconds: 30, endSeconds: 40, speakerName: "a", text: "둘째" },
            { startSeconds: 2, endSeconds: 10, speakerName: "b", text: "첫째" },
          ],
        }),
      ),
    );

    const report = await requestStudentReport("s1", "token");

    expect(report.transcript.map((segment) => segment.text)).toEqual(["첫째", "둘째"]);
  });

  it("추천이 없으면 빈 배열이다 — 오류가 아니다", async () => {
    respondWith(envelope(reportWith({ recommendations: [] })));

    const report = await requestStudentReport("s1", "token");

    expect(report.recommendations).toEqual([]);
  });

  it("추천이 5개를 넘으면 잘라낸다 — 0~5 계약(REPORT-S-002)", async () => {
    const recommendation = (index: number) => ({
      id: index,
      title: `추천 ${index}`,
      reason: "근거",
      startSeconds: index * 60,
      endSeconds: index * 60 + 30,
      recommendationType: "QUESTION",
    });
    respondWith(
      envelope(reportWith({ recommendations: [0, 1, 2, 3, 4, 5].map(recommendation) })),
    );

    const report = await requestStudentReport("s1", "token");

    expect(report.recommendations).toHaveLength(5);
    // TSID 는 문자열로만 다룬다.
    expect(report.recommendations[0].id).toBe("0");
  });

  it("seekTimestamp 가 비정상이면 0 으로 낮춘다", async () => {
    respondWith(envelope(reportWith({ seekTimestamp: -5 })));

    const report = await requestStudentReport("s1", "token");

    expect(report.seekTimestamp).toBe(0);
  });

  it("403 이면 상태를 담아 던진다 — 비참여자·타 학생 추천 접근", async () => {
    respondWith({ isSuccess: false, code: "REPORT403", message: "forbidden" }, 403);

    await expect(requestStudentReport("s1", "token")).rejects.toMatchObject({
      name: "StudentReportError",
      status: 403,
    });
  });

  it("네트워크 실패는 상태 0 이다", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new Error("offline")));

    await expect(requestStudentReport("s1", "token")).rejects.toMatchObject({ status: 0 });
  });

  it("봉투가 깨지면 던진다", async () => {
    respondWith({ isSuccess: true, data: null });

    await expect(requestStudentReport("s1", "token")).rejects.toBeInstanceOf(StudentReportError);
  });
});
