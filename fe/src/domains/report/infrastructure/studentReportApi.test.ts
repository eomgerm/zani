import { afterEach, describe, expect, it, vi } from "vitest";

import { requestStudentReport, StudentReportError } from "./studentReportApi";

const envelope = (data: unknown) => ({ isSuccess: true, code: "COMMON200", message: "ok", data });

const recommendation = (index: number, overrides: Record<string, unknown> = {}) => ({
  recommendationType: "CONFUSED",
  title: `추천 ${index}`,
  description: `근거 ${index}`,
  startSeconds: index * 60,
  endSeconds: index * 60 + 30,
  priority: index,
  ...overrides,
});

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

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("requestStudentReport", () => {
  it("학생 리포트 경로를 부르고 Bearer 토큰을 보낸다", async () => {
    respondWith(
      envelope({ activity: {}, participationSummary: "요약", recommendations: [] }),
    );

    await requestStudentReport("session/1", "token");

    const [url, init] = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(String(url)).toContain("/api/v1/sessions/session%2F1/reports/student");
    expect(init).toMatchObject({
      credentials: "include",
      headers: { Accept: "application/json", Authorization: "Bearer token" },
    });
  });

  it("활동 집계·참여 요약·복습 추천을 읽는다", async () => {
    respondWith(
      envelope({
        activity: { publicChatCount: 3, confusedCount: 2, missedCount: 1, questionCount: 2 },
        participationSummary: "공개 채팅으로 질문했어요.",
        recommendations: [recommendation(1)],
      }),
    );

    const report = await requestStudentReport("s1", "token");

    expect(report.activity).toEqual({
      publicChatCount: 3,
      confusedCount: 2,
      missedCount: 1,
      questionCount: 2,
    });
    expect(report.participationSummary).toBe("공개 채팅으로 질문했어요.");
    expect(report.recommendations[0]).toEqual({
      recommendationType: "CONFUSED",
      title: "추천 1",
      description: "근거 1",
      startSeconds: 60,
      endSeconds: 90,
    });
  });

  it("활동 횟수를 0 이상 정수로 낮춘다 — '3.9회'·'-2회' 를 화면에 내보내지 않는다", async () => {
    respondWith(
      envelope({
        activity: { publicChatCount: 3.9, confusedCount: -2, missedCount: Number.NaN },
        participationSummary: "요약",
        recommendations: [],
      }),
    );

    const report = await requestStudentReport("s1", "token");

    expect(report.activity).toMatchObject({
      publicChatCount: 3,
      confusedCount: 0,
      missedCount: 0,
    });
  });

  it("질문 수는 판정이 없으면 null, 0 이면 0 이다 — 둘을 뭉치지 않는다", async () => {
    const activityWith = (questionCount: unknown) =>
      envelope({
        activity: { publicChatCount: 1, confusedCount: 0, missedCount: 0, questionCount },
        participationSummary: "요약",
        recommendations: [],
      });

    // 0 은 모델이 "질문이 없었다"고 판단한 값이다. null 로 낮추면 판정을 버리는 셈이 된다.
    respondWith(activityWith(0));
    await expect(requestStudentReport("s1", "token")).resolves.toMatchObject({
      activity: { questionCount: 0 },
    });

    for (const absent of [undefined, null, -1, Number.NaN, "2"]) {
      respondWith(activityWith(absent));
      await expect(requestStudentReport("s1", "token")).resolves.toMatchObject({
        activity: { questionCount: null },
      });
    }
  });

  it("activity 가 아예 없어도 0 으로 그린다 — 요약·추천까지 함께 잃지 않는다", async () => {
    respondWith(envelope({ participationSummary: "요약", recommendations: [] }));

    const report = await requestStudentReport("s1", "token");

    expect(report.activity).toEqual({
      publicChatCount: 0,
      confusedCount: 0,
      missedCount: 0,
      questionCount: null,
    });
    expect(report.participationSummary).toBe("요약");
  });

  it("참여 요약이 없으면 빈 문자열이다 — 한 필드 때문에 집계와 추천을 버리지 않는다", async () => {
    respondWith(
      envelope({
        activity: { publicChatCount: 1, confusedCount: 0, missedCount: 0 },
        participationSummary: null,
        recommendations: [recommendation(1)],
      }),
    );

    const report = await requestStudentReport("s1", "token");

    expect(report.participationSummary).toBe("");
    expect(report.recommendations).toHaveLength(1);
  });

  it("249 가 정한 다섯 가지 근거 유형과 모르는 값을 모두 그대로 둔다", async () => {
    const types = ["CONFUSED", "MISSED", "NO_RESPONSE", "LOW_ENGAGEMENT", "QUESTION"];
    respondWith(
      envelope({
        activity: {},
        participationSummary: "요약",
        recommendations: types.map((type, index) => recommendation(index, {
          recommendationType: type,
        })),
      }),
    );

    const report = await requestStudentReport("s1", "token");

    expect(report.recommendations.map((item) => item.recommendationType)).toEqual(types);
  });

  it("근거 유형이 문자열이 아니면 빈 문자열로 둔다 — 추천 자체는 버리지 않는다", async () => {
    respondWith(
      envelope({
        activity: {},
        participationSummary: "요약",
        recommendations: [recommendation(1, { recommendationType: 7 })],
      }),
    );

    const report = await requestStudentReport("s1", "token");

    expect(report.recommendations[0].recommendationType).toBe("");
  });

  it("제목이나 시작 시각이 없는 추천만 버린다 — 목록 전체를 버리지 않는다", async () => {
    respondWith(
      envelope({
        activity: {},
        participationSummary: "요약",
        recommendations: [
          recommendation(1),
          { description: "제목 없음", startSeconds: 10 },
          recommendation(2, { startSeconds: "60" }),
        ],
      }),
    );

    const report = await requestStudentReport("s1", "token");

    expect(report.recommendations).toHaveLength(1);
    expect(report.recommendations[0].title).toBe("추천 1");
  });

  it("끝 시각이 없으면 시작 시각으로 둔다", async () => {
    respondWith(
      envelope({
        activity: {},
        participationSummary: "요약",
        recommendations: [recommendation(1, { endSeconds: null })],
      }),
    );

    const report = await requestStudentReport("s1", "token");

    expect(report.recommendations[0].endSeconds).toBe(60);
  });

  it("추천이 없으면 빈 배열이다 — 오류가 아니다(REPORT-S-005)", async () => {
    respondWith(envelope({ activity: {}, participationSummary: "요약", recommendations: [] }));

    await expect(requestStudentReport("s1", "token")).resolves.toMatchObject({
      recommendations: [],
    });
  });

  it("추천이 5개를 넘으면 잘라낸다 — 0~5 계약(REPORT-S-002)", async () => {
    respondWith(
      envelope({
        activity: {},
        participationSummary: "요약",
        recommendations: Array.from({ length: 7 }, (_, index) => recommendation(index)),
      }),
    );

    const report = await requestStudentReport("s1", "token");

    expect(report.recommendations).toHaveLength(5);
  });

  it.each([403, 404, 409])("HTTP %s 를 상태가 보존된 오류로 올린다", async (status) => {
    respondWith({ isSuccess: false, code: "REPORT", message: "no" }, status);

    await expect(requestStudentReport("s1", "token")).rejects.toMatchObject({
      name: "StudentReportError",
      status,
    });
  });

  it("네트워크 실패는 상태 0 이다", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new Error("offline")));

    await expect(requestStudentReport("s1", "token")).rejects.toMatchObject({ status: 0 });
  });

  it("JSON 과 성공 봉투가 잘못되면 계약 오류다", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => Promise.reject("bad") }),
    );
    await expect(requestStudentReport("s1", "token")).rejects.toBeInstanceOf(StudentReportError);

    respondWith({ isSuccess: false, data: {} });
    await expect(requestStudentReport("s1", "token")).rejects.toBeInstanceOf(StudentReportError);

    respondWith({ isSuccess: true, data: null });
    await expect(requestStudentReport("s1", "token")).rejects.toBeInstanceOf(StudentReportError);
  });
});
