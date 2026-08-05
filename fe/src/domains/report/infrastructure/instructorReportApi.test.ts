import { afterEach, describe, expect, it, vi } from "vitest";

import { InstructorReportError, requestInstructorReport } from "./instructorReportApi";

const ok = (data: unknown) =>
  new Response(JSON.stringify({ isSuccess: true, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });

const stubFetch = (response: Response | Promise<Response>) => {
  const spy = vi.fn().mockResolvedValue(response);
  vi.stubGlobal("fetch", spy);
  return spy;
};

/** 필수 뼈대만 채운 응답. 각 테스트가 관심 있는 필드만 덮어쓴다. */
const body = (overrides: Record<string, unknown> = {}) => ({
  overallFeedback: "전반적으로 흐름이 좋았습니다.",
  stats: { studentCount: 32, durationSeconds: 7500, questionCount: 184, alertCount: 7 },
  scores: [{ evaluationType: "DELIVERY", score: 88 }],
  insights: [],
  tips: [],
  sections: [],
  ...overrides,
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("requestInstructorReport", () => {
  it("강사 전용 경로를 Bearer 토큰과 함께 부른다", async () => {
    const spy = stubFetch(ok(body()));

    await requestInstructorReport("9200001", "token-abc");

    const [url, init] = spy.mock.calls[0] as [string, RequestInit];
    expect(url).toContain("/api/v1/sessions/9200001/reports/instructor");
    expect((init.headers as Record<string, string>).Authorization).toBe("Bearer token-abc");
  });

  it("구간 시각을 ms 에서 초로 바꿔 준다", async () => {
    stubFetch(
      ok(
        body({
          insights: [
            {
              insightType: "LOW_FOCUS_SECTION",
              content: "예외 처리 구간에서 집중도가 낮았어요.",
              startedOffsetMs: 4_800_000,
              endedOffsetMs: 6_000_000,
            },
          ],
        }),
      ),
    );

    const report = await requestInstructorReport("9200001", "token");

    // 화면의 이동 요청과 참여도 타임라인이 전부 초를 쓴다. 1000 배 어긋나면 엉뚱한 자리로 간다.
    expect(report.insights[0].startSeconds).toBe(4800);
    expect(report.insights[0].endSeconds).toBe(6000);
  });

  it("수업 전체를 가리키는 인사이트는 구간을 null 로 남긴다", async () => {
    stubFetch(
      ok(
        body({
          insights: [
            {
              insightType: "OVERALL",
              content: "후반부로 갈수록 회복됐어요.",
              startedOffsetMs: null,
              endedOffsetMs: null,
            },
          ],
        }),
      ),
    );

    const report = await requestInstructorReport("9200001", "token");

    // 0 으로 바꾸면 "수업 시작 지점" 이라는 없는 사실이 생긴다.
    expect(report.insights[0].startSeconds).toBeNull();
    expect(report.insights[0].endSeconds).toBeNull();
  });

  it("질문 수를 셀 수 없으면 0 이 아니라 null 로 읽는다", async () => {
    stubFetch(
      ok(body({ stats: { studentCount: 3, durationSeconds: 600, questionCount: null, alertCount: 0 } })),
    );

    const report = await requestInstructorReport("9200001", "token");

    // 0 으로 접으면 "아무도 질문하지 않았다" 라는 다른 사실이 된다.
    expect(report.stats.questionCount).toBeNull();
    expect(report.stats.alertCount).toBe(0);
  });

  it("질문이 정말 0 건이면 0 을 그대로 지킨다", async () => {
    stubFetch(
      ok(body({ stats: { studentCount: 3, durationSeconds: 600, questionCount: 0, alertCount: 0 } })),
    );

    const report = await requestInstructorReport("9200001", "token");

    expect(report.stats.questionCount).toBe(0);
  });

  it("깨진 항목만 버리고 나머지 평가는 지킨다", async () => {
    stubFetch(
      ok(
        body({
          scores: [
            { evaluationType: "DELIVERY", score: 88 },
            { evaluationType: "", score: 70 },
            { evaluationType: "INTERACTION", score: "높음" },
            { evaluationType: "STRUCTURE_FLOW", score: 84 },
          ],
        }),
      ),
    );

    const report = await requestInstructorReport("9200001", "token");

    // 하나가 깨졌다고 나머지 평가까지 감출 이유가 없다.
    expect(report.scores.map((s) => s.evaluationType)).toEqual(["DELIVERY", "STRUCTURE_FLOW"]);
  });

  it("0~100 을 벗어난 점수를 잘라 도넛이 넘치지 않게 한다", async () => {
    stubFetch(
      ok(
        body({
          scores: [
            { evaluationType: "DELIVERY", score: 140 },
            { evaluationType: "INTERACTION", score: -5 },
          ],
        }),
      ),
    );

    const report = await requestInstructorReport("9200001", "token");

    expect(report.scores.map((s) => s.score)).toEqual([100, 0]);
  });

  it("인사이트를 이른 구간부터 세우고 수업 전체를 앞에 둔다", async () => {
    stubFetch(
      ok(
        body({
          insights: [
            { insightType: "A", content: "늦은 구간", startedOffsetMs: 600_000 },
            { insightType: "B", content: "전체", startedOffsetMs: null },
            { insightType: "C", content: "이른 구간", startedOffsetMs: 60_000 },
          ],
        }),
      ),
    );

    const report = await requestInstructorReport("9200001", "token");

    expect(report.insights.map((i) => i.content)).toEqual(["전체", "이른 구간", "늦은 구간"]);
  });

  it("제목만 있는 팁도 카드로 남긴다", async () => {
    stubFetch(
      ok(
        body({
          tips: [
            { tipType: "INTERACTION", title: "질문 시간 확보", content: "" },
            { tipType: "NONE", title: "", content: "" },
          ],
        }),
      ),
    );

    const report = await requestInstructorReport("9200001", "token");

    expect(report.tips).toHaveLength(1);
    expect(report.tips[0].title).toBe("질문 시간 확보");
  });

  it("집계가 통째로 빠져도 던지지 않고 빈 값으로 그린다", async () => {
    stubFetch(ok(body({ stats: undefined })));

    const report = await requestInstructorReport("9200001", "token");

    expect(report.stats.studentCount).toBe(0);
    expect(report.stats.questionCount).toBeNull();
  });

  it("403 과 404 를 상태 그대로 올려 훅이 갈라 볼 수 있게 한다", async () => {
    for (const status of [403, 404]) {
      stubFetch(new Response("{}", { status }));

      await expect(requestInstructorReport("9200001", "token")).rejects.toMatchObject({
        name: "InstructorReportError",
        status,
      });
    }
  });

  it("응답을 못 받으면 상태 0 으로 올려 403·404 와 구분한다", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new TypeError("network down")));

    await expect(requestInstructorReport("9200001", "token")).rejects.toMatchObject({ status: 0 });
  });

  it("isSuccess 가 거짓인 봉투는 계약 위반으로 던진다", async () => {
    stubFetch(
      new Response(JSON.stringify({ isSuccess: false, data: body() }), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      }),
    );

    await expect(requestInstructorReport("9200001", "token")).rejects.toBeInstanceOf(
      InstructorReportError,
    );
  });
});
