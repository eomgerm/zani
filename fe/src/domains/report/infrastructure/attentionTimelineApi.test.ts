import { afterEach, describe, expect, it, vi } from "vitest";

import {
  AttentionTimelineError,
  requestGroupAttentionTimeline,
  requestStudentAttentionTimeline,
} from "./attentionTimelineApi";

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

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("requestStudentAttentionTimeline", () => {
  it("집중 흐름을 1~4 값 그대로 읽는다 — 퍼센트로 바꾸지 않는다", async () => {
    respondWith(
      envelope({
        durationSeconds: 90,
        focusFlow: { intervalSeconds: 30, points: [{ offsetSeconds: 0, focusLevel: 3.67 }] },
        stateIntervals: [],
        sections: [],
      }),
    );

    const timeline = await requestStudentAttentionTimeline("s1", "token");

    expect(timeline.focusFlow.intervalSeconds).toBe(30);
    expect(timeline.focusFlow.points[0].focusLevel).toBe(3.67);
  });

  it("개인 경로를 부르고 Bearer 토큰을 보낸다", async () => {
    respondWith(
      envelope({
        durationSeconds: 0,
        focusFlow: { intervalSeconds: 30, points: [] },
        stateIntervals: [],
        sections: [],
      }),
    );

    await requestStudentAttentionTimeline("s1", "token");

    const [url, init] = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(String(url)).toContain("/api/v1/sessions/s1/reports/attention/me");
    expect((init as RequestInit).headers).toMatchObject({ Authorization: "Bearer token" });
  });

  it("focusLevel 이 null 이면 null 로 둔다 — 0 이나 1 로 바꾸지 않는다", async () => {
    respondWith(
      envelope({
        durationSeconds: 30,
        focusFlow: { intervalSeconds: 30, points: [{ offsetSeconds: 0, focusLevel: null }] },
        stateIntervals: [],
        sections: [],
      }),
    );

    const timeline = await requestStudentAttentionTimeline("s1", "token");

    expect(timeline.focusFlow.points[0].focusLevel).toBeNull();
  });

  it("숫자가 아닌 focusLevel 은 null 로 낮춘다", async () => {
    respondWith(
      envelope({
        durationSeconds: 30,
        focusFlow: { intervalSeconds: 30, points: [{ offsetSeconds: 0, focusLevel: "3.67" }] },
        stateIntervals: [],
        sections: [],
      }),
    );

    const timeline = await requestStudentAttentionTimeline("s1", "token");

    expect(timeline.focusFlow.points[0].focusLevel).toBeNull();
  });

  it("상태 구간을 그대로 읽고 모르는 state 는 버린다", async () => {
    respondWith(
      envelope({
        durationSeconds: 120,
        focusFlow: { intervalSeconds: 30, points: [] },
        stateIntervals: [
          { startSeconds: 0, endSeconds: 60, state: "GOOD" },
          { startSeconds: 60, endSeconds: 90, state: "DANCING" },
          { startSeconds: 90, endSeconds: 120, state: "CAMERA_OFF" },
        ],
        sections: [],
      }),
    );

    const timeline = await requestStudentAttentionTimeline("s1", "token");

    expect(timeline.stateIntervals).toEqual([
      { startSeconds: 0, endSeconds: 60, state: "GOOD" },
      { startSeconds: 90, endSeconds: 120, state: "CAMERA_OFF" },
    ]);
  });

  it("내용 구간이 없으면 빈 배열이다 — 오류가 아니다", async () => {
    respondWith(
      envelope({
        durationSeconds: 30,
        focusFlow: { intervalSeconds: 30, points: [] },
        stateIntervals: [],
        sections: [],
      }),
    );

    const timeline = await requestStudentAttentionTimeline("s1", "token");

    expect(timeline.sections).toEqual([]);
  });

  it("sections 키가 아예 없어도 빈 배열로 채운다", async () => {
    respondWith(
      envelope({
        durationSeconds: 30,
        focusFlow: { intervalSeconds: 30, points: [] },
        stateIntervals: [],
      }),
    );

    const timeline = await requestStudentAttentionTimeline("s1", "token");

    expect(timeline.sections).toEqual([]);
  });

  it("내용 구간 summary 를 문자열 또는 null 로 읽는다", async () => {
    respondWith(
      envelope({
        durationSeconds: 60,
        focusFlow: { intervalSeconds: 30, points: [] },
        stateIntervals: [],
        sections: [
          {
            startSeconds: 0,
            endSeconds: 30,
            title: "함수의 정의",
            summary: "입력과 출력의 관계를 설명했어요.",
            focusLevel: 3.2,
          },
          { startSeconds: 30, endSeconds: 60, title: "함수의 활용", summary: 42, focusLevel: null },
        ],
      }),
    );

    const timeline = await requestStudentAttentionTimeline("s1", "token");

    expect(timeline.sections.map((section) => section.summary)).toEqual([
      "입력과 출력의 관계를 설명했어요.",
      null,
    ]);
  });

  it("intervalSeconds 가 없으면 집중 흐름 기본값 30초를 쓴다", async () => {
    respondWith(
      envelope({
        durationSeconds: 30,
        focusFlow: { points: [] },
        stateIntervals: [],
        sections: [],
      }),
    );

    const timeline = await requestStudentAttentionTimeline("s1", "token");

    expect(timeline.focusFlow.intervalSeconds).toBe(30);
  });

  it("focusFlow 가 없으면 계약 위반이라 던진다", async () => {
    respondWith(envelope({ durationSeconds: 30, stateIntervals: [], sections: [] }));

    await expect(requestStudentAttentionTimeline("s1", "token")).rejects.toBeInstanceOf(
      AttentionTimelineError,
    );
  });
});

describe("requestGroupAttentionTimeline", () => {
  it("격자 두 개를 각자 간격과 함께 읽는다", async () => {
    respondWith(
      envelope({
        durationSeconds: 60,
        focusFlow: {
          intervalSeconds: 30,
          points: [{ offsetSeconds: 0, focusLevel: 3.2, eligibleCount: 28 }],
        },
        signals: {
          intervalSeconds: 5,
          points: [
            {
              offsetSeconds: 0,
              connectedCount: 30,
              eligibleCount: 28,
              checkNeededRatio: 0.32,
              cameraOffRatio: 0.07,
              confusedRatio: 0.1,
              missedRatio: 0.1,
              nonResponseRatio: 0.07,
              unmeasurableRatio: 0.05,
            },
          ],
        },
        distractedIntervals: [{ startSeconds: 0, endSeconds: 20 }],
        sections: [{ startSeconds: 0, endSeconds: 60, title: "함수의 정의", focusLevel: 3.2 }],
      }),
    );

    const timeline = await requestGroupAttentionTimeline("s1", "token");

    expect(timeline.focusFlow.intervalSeconds).toBe(30);
    expect(timeline.signals.intervalSeconds).toBe(5);
    expect(timeline.focusFlow.points[0].eligibleCount).toBe(28);
    expect(timeline.signals.points[0].checkNeededRatio).toBe(0.32);
    expect(timeline.sections[0].title).toBe("함수의 정의");
  });

  it("집단 경로를 부르고 Bearer 토큰을 보낸다", async () => {
    respondWith(
      envelope({
        durationSeconds: 0,
        focusFlow: { intervalSeconds: 30, points: [] },
        signals: { intervalSeconds: 5, points: [] },
        distractedIntervals: [],
        sections: [],
      }),
    );

    await requestGroupAttentionTimeline("s1", "token");

    const [url, init] = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(String(url)).toContain("/api/v1/sessions/s1/reports/attention/group");
    expect((init as RequestInit).headers).toMatchObject({ Authorization: "Bearer token" });
  });

  it("관측이 없는 빈 시계열도 정상 응답이다", async () => {
    respondWith(
      envelope({
        durationSeconds: 0,
        focusFlow: { intervalSeconds: 30, points: [] },
        signals: { intervalSeconds: 5, points: [] },
        distractedIntervals: [],
        sections: [],
      }),
    );

    const timeline = await requestGroupAttentionTimeline("s1", "token");

    expect(timeline.focusFlow.points).toEqual([]);
    expect(timeline.signals.points).toEqual([]);
  });

  it("비율 null 을 0 으로 바꾸지 않는다", async () => {
    respondWith(
      envelope({
        durationSeconds: 5,
        focusFlow: { intervalSeconds: 30, points: [] },
        signals: {
          intervalSeconds: 5,
          points: [
            {
              offsetSeconds: 0,
              connectedCount: 4,
              eligibleCount: 4,
              checkNeededRatio: null,
              cameraOffRatio: null,
              confusedRatio: null,
              missedRatio: null,
              nonResponseRatio: null,
              unmeasurableRatio: null,
            },
          ],
        },
        distractedIntervals: [],
        sections: [],
      }),
    );

    const timeline = await requestGroupAttentionTimeline("s1", "token");

    // 0 으로 바뀌면 회색 공백이어야 할 구간이 "집중이 완벽했다"로 뒤집힌다.
    expect(timeline.signals.points[0].checkNeededRatio).toBeNull();
    expect(timeline.signals.points[0].eligibleCount).toBe(4);
  });

  it("숫자가 아닌 필드를 가진 점 하나만 버리고 시계열을 살린다", async () => {
    respondWith(
      envelope({
        durationSeconds: 60,
        focusFlow: {
          intervalSeconds: 30,
          points: [
            { offsetSeconds: 0, focusLevel: 3.2, eligibleCount: 28 },
            { offsetSeconds: "삼십", focusLevel: 3.0, eligibleCount: 28 },
          ],
        },
        signals: { intervalSeconds: 5, points: [] },
        distractedIntervals: [],
        sections: [],
      }),
    );

    const timeline = await requestGroupAttentionTimeline("s1", "token");

    expect(timeline.focusFlow.points).toHaveLength(1);
  });

  it("signals 가 없으면 계약 위반이라 던진다", async () => {
    respondWith(
      envelope({
        durationSeconds: 60,
        focusFlow: { intervalSeconds: 30, points: [] },
        distractedIntervals: [],
        sections: [],
      }),
    );

    await expect(requestGroupAttentionTimeline("s1", "token")).rejects.toBeInstanceOf(
      AttentionTimelineError,
    );
  });

  it("403 은 상태 코드를 담은 오류로 던진다", async () => {
    respondWith({}, 403);

    await expect(requestGroupAttentionTimeline("s1", "token")).rejects.toMatchObject({
      name: "AttentionTimelineError",
      status: 403,
    });
  });

  it("409 도 상태 코드를 담아 던진다", async () => {
    respondWith({}, 409);

    await expect(requestGroupAttentionTimeline("s1", "token")).rejects.toMatchObject({
      status: 409,
    });
  });

  it("응답을 못 받으면 상태 0 이다 — 403·409 와 구분해야 한다", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new Error("offline")));

    await expect(requestGroupAttentionTimeline("s1", "token")).rejects.toMatchObject({ status: 0 });
  });

  it("isSuccess 가 아니면 봉투 위반으로 던진다", async () => {
    respondWith({ isSuccess: false, code: "X", message: "", data: {} });

    await expect(requestGroupAttentionTimeline("s1", "token")).rejects.toBeInstanceOf(
      AttentionTimelineError,
    );
  });
});
