import { afterEach, describe, expect, it, vi } from "vitest";

import { requestStudentClip, StudentClipError } from "./studentClipApi";

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

const clipWith = (overrides: Record<string, unknown> = {}) => ({
  recordingUrl: "https://media.example/lecture.mp4?token=abc",
  durationSeconds: 5430,
  transcript: [
    { startSeconds: 2, endSeconds: 32, speakerName: "박서준", text: "오늘은 상태 관리를 다룹니다." },
  ],
  seekTimestamp: 0,
  ...overrides,
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("requestStudentClip", () => {
  it("학생 리포트 경로를 부르고 Bearer 토큰을 보낸다", async () => {
    respondWith(envelope(clipWith()));

    await requestStudentClip("s1", "token");

    const [url, init] = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(String(url)).toContain("/api/v1/sessions/s1/reports/student");
    expect((init as RequestInit).headers).toMatchObject({ Authorization: "Bearer token" });
  });

  it("녹화 URL·전사·초기 위치를 그대로 읽는다", async () => {
    respondWith(envelope(clipWith({ seekTimestamp: 1440 })));

    const clip = await requestStudentClip("s1", "token");

    expect(clip.recordingUrl).toBe("https://media.example/lecture.mp4?token=abc");
    expect(clip.durationSeconds).toBe(5430);
    expect(clip.seekTimestamp).toBe(1440);
    expect(clip.transcript[0]).toEqual({
      startSeconds: 2,
      endSeconds: 32,
      speakerName: "박서준",
      text: "오늘은 상태 관리를 다룹니다.",
    });
  });

  it("녹화 URL 이 없으면 null 이다 — 빈 문자열을 src 로 흘리지 않는다", async () => {
    respondWith(envelope(clipWith({ recordingUrl: "" })));

    const clip = await requestStudentClip("s1", "token");

    expect(clip.recordingUrl).toBeNull();
  });

  it("학습 리포트 계약(112)이 와도 녹화 없음으로 떨어진다 — 같은 경로가 아직 갈리지 않았다", async () => {
    respondWith(
      envelope({
        activity: { publicChatCount: 3, confusedCount: 1, missedCount: 0 },
        participationSummary: "공개 채팅으로 질문했어요.",
        recommendations: [],
      }),
    );

    const clip = await requestStudentClip("s1", "token");

    expect(clip).toEqual({
      recordingUrl: null,
      durationSeconds: 0,
      transcript: [],
      seekTimestamp: 0,
    });
  });

  it("깨진 전사 행만 버린다 — 전사 전체를 버리지 않는다", async () => {
    respondWith(
      envelope(
        clipWith({
          transcript: [
            { startSeconds: 2, endSeconds: 10, speakerName: "박서준", text: "정상 행" },
            { endSeconds: 20, speakerName: "박서준", text: "시작 시각 없음" },
            { startSeconds: 30, endSeconds: 40, speakerName: "박서준", text: "" },
            { startSeconds: 50, speakerName: 7, text: "화자 이름이 깨짐" },
          ],
        }),
      ),
    );

    const clip = await requestStudentClip("s1", "token");

    expect(clip.transcript).toHaveLength(2);
    expect(clip.transcript[0].text).toBe("정상 행");
    // 화자 이름이 깨져도 본문은 살린다. 이름만 비운다.
    expect(clip.transcript[1]).toMatchObject({ speakerName: "", text: "화자 이름이 깨짐" });
  });

  it("전사가 뒤섞여 와도 시작 시각 순으로 정렬한다 — 커서 계산이 정렬을 전제한다", async () => {
    respondWith(
      envelope(
        clipWith({
          transcript: [
            { startSeconds: 30, endSeconds: 40, speakerName: "a", text: "둘째" },
            { startSeconds: 2, endSeconds: 10, speakerName: "b", text: "첫째" },
          ],
        }),
      ),
    );

    const clip = await requestStudentClip("s1", "token");

    expect(clip.transcript.map((segment) => segment.text)).toEqual(["첫째", "둘째"]);
  });

  it("seekTimestamp 가 비정상이면 0 으로 낮춘다", async () => {
    respondWith(envelope(clipWith({ seekTimestamp: -5 })));

    const clip = await requestStudentClip("s1", "token");

    expect(clip.seekTimestamp).toBe(0);
  });

  it("403 이면 상태를 담아 던진다 — 비참여자 접근", async () => {
    respondWith({ isSuccess: false, code: "REPORT403", message: "forbidden" }, 403);

    await expect(requestStudentClip("s1", "token")).rejects.toMatchObject({
      name: "StudentClipError",
      status: 403,
    });
  });

  it("네트워크 실패는 상태 0 이다", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new Error("offline")));

    await expect(requestStudentClip("s1", "token")).rejects.toMatchObject({ status: 0 });
  });

  it("봉투가 깨지면 던진다", async () => {
    respondWith({ isSuccess: true, data: null });

    await expect(requestStudentClip("s1", "token")).rejects.toBeInstanceOf(StudentClipError);
  });
});
