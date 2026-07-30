import { afterEach, describe, expect, it, vi } from "vitest";

import {
  AttentionTimelineError,
  requestGroupAttentionTimeline,
  requestStudentAttentionTimeline,
} from "./attentionTimelineApi";

const envelope = (data: unknown) => ({ isSuccess: true, code: "COMMON200", message: "ok", data });

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

describe("requestGroupAttentionTimeline", () => {
  it("keeps null ratios as null instead of turning them into zero", async () => {
    respondWith(
      envelope({
        intervalSeconds: 5,
        durationSeconds: 300,
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
        distractedIntervals: [],
      }),
    );

    const timeline = await requestGroupAttentionTimeline("s1", "token");

    // 0 으로 바뀌면 회색 공백이어야 할 구간이 "집중이 완벽했다"로 뒤집힌다.
    expect(timeline.points[0].checkNeededRatio).toBeNull();
    expect(timeline.points[0].eligibleCount).toBe(4);
  });

  it("sends the bearer token and hits the group path", async () => {
    respondWith(envelope({ intervalSeconds: 5, durationSeconds: 0, points: [], distractedIntervals: [] }));

    await requestGroupAttentionTimeline("s1", "token");

    const [url, init] = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(String(url)).toContain("/api/v1/sessions/s1/reports/attention/group");
    expect((init as RequestInit).headers).toMatchObject({ Authorization: "Bearer token" });
  });

  it("accepts an empty series as a valid answer", async () => {
    respondWith(envelope({ intervalSeconds: 5, durationSeconds: 0, points: [], distractedIntervals: [] }));

    await expect(requestGroupAttentionTimeline("s1", "token")).resolves.toMatchObject({ points: [] });
  });

  it("throws with the status so the card can tell 403 from 409", async () => {
    respondWith({}, 409);

    await expect(requestGroupAttentionTimeline("s1", "token")).rejects.toMatchObject({
      name: "AttentionTimelineError",
      status: 409,
    });
  });

  it("rejects a malformed envelope", async () => {
    respondWith({ isSuccess: false, data: null });

    await expect(requestGroupAttentionTimeline("s1", "token")).rejects.toBeInstanceOf(AttentionTimelineError);
  });

  it("drops points that are missing required fields", async () => {
    respondWith(
      envelope({
        intervalSeconds: 5,
        durationSeconds: 10,
        points: [
          { offsetSeconds: 0 },
          {
            offsetSeconds: 5,
            connectedCount: 6,
            eligibleCount: 6,
            checkNeededRatio: 0.5,
            cameraOffRatio: 0,
            confusedRatio: 0.5,
            missedRatio: 0,
            nonResponseRatio: 0,
            unmeasurableRatio: 0,
          },
        ],
        distractedIntervals: [],
      }),
    );

    const timeline = await requestGroupAttentionTimeline("s1", "token");

    expect(timeline.points).toHaveLength(1);
    expect(timeline.points[0].offsetSeconds).toBe(5);
  });

  it("reports a network failure as status 0", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new TypeError("network down")));

    await expect(requestGroupAttentionTimeline("s1", "token")).rejects.toMatchObject({ status: 0 });
  });
});

describe("requestStudentAttentionTimeline", () => {
  it("hits the personal path and keeps unknown states out", async () => {
    respondWith(
      envelope({
        intervalSeconds: 5,
        durationSeconds: 10,
        points: [
          { offsetSeconds: 0, focusPercent: null, state: "CAMERA_OFF" },
          { offsetSeconds: 5, focusPercent: 80, state: "NOT_A_REAL_STATE" },
        ],
      }),
    );

    const timeline = await requestStudentAttentionTimeline("s1", "token");

    const [url] = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(String(url)).toContain("/api/v1/sessions/s1/reports/attention/me");
    expect(timeline.points[0].state).toBe("CAMERA_OFF");
    // 모르는 상태는 버리지 말고 null 로 낮춘다. 점 자체가 사라지면 시간축에 구멍이 난다.
    expect(timeline.points[1].state).toBeNull();
    expect(timeline.points[1].focusPercent).toBe(80);
  });
});
