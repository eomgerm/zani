import { afterEach, describe, expect, it, vi } from "vitest";

import { requestSessionSummary, SessionSummaryError } from "./sessionSummaryApi";

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

const SUMMARY = "이번 수업은 지역 상태에서 출발해 Context 리렌더링으로 이어졌습니다.";

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("requestSessionSummary", () => {
  it("수업 요약 경로를 부르고 Bearer 토큰을 보낸다", async () => {
    respondWith(envelope({ summary: SUMMARY }));

    await requestSessionSummary("s1", "token");

    const [url, init] = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(String(url)).toContain("/api/v1/sessions/s1/reports/summary");
    expect((init as RequestInit).headers).toMatchObject({ Authorization: "Bearer token" });
  });

  it("요약 문단을 그대로 읽는다", async () => {
    respondWith(envelope({ summary: SUMMARY }));

    await expect(requestSessionSummary("s1", "token")).resolves.toEqual({
      summary: SUMMARY,
      sections: [],
    });
  });

  it("구간을 초 단위로 옮겨 읽는다", async () => {
    respondWith(
      envelope({
        summary: SUMMARY,
        sections: [
          {
            startedOffsetMs: 0,
            endedOffsetMs: 600_000,
            title: "상태 관리 개요",
            summary: "지역 상태와 전역 상태를 가르는 기준을 설명했다.",
          },
        ],
      }),
    );

    await expect(requestSessionSummary("s1", "token")).resolves.toEqual({
      summary: SUMMARY,
      sections: [
        {
          startOffsetMs: 0,
          startSeconds: 0,
          endSeconds: 600,
          title: "상태 관리 개요",
          summary: "지역 상태와 전역 상태를 가르는 기준을 설명했다.",
        },
      ],
    });
  });

  it("구간이 없거나 배열이 아니면 빈 목록이다 — 요약은 그대로 그린다", async () => {
    respondWith(envelope({ summary: SUMMARY, sections: null }));

    // 구간을 싣기 전 서버와도 붙는다. 요약만 있는 세션에 오류를 내면 그 세션은 아무것도 못 본다.
    await expect(requestSessionSummary("s1", "token")).resolves.toMatchObject({ sections: [] });
  });

  it("깨진 구간만 버리고 나머지는 남긴다", async () => {
    respondWith(
      envelope({
        summary: SUMMARY,
        sections: [
          { startedOffsetMs: 0, endedOffsetMs: 600_000, title: "", summary: "" },
          { startedOffsetMs: 600_000, endedOffsetMs: 1_200_000, title: "제목만 있는 구간" },
        ],
      }),
    );

    const result = await requestSessionSummary("s1", "token");

    // 요약이 없는 구간은 제목으로도 자리를 말한다. 제목·요약이 둘 다 빈 구간만 버린다.
    expect(result.sections).toEqual([
      {
        startOffsetMs: 600_000,
        startSeconds: 600,
        endSeconds: 1200,
        title: "제목만 있는 구간",
        summary: "",
      },
    ]);
  });

  it("세션 id를 이스케이프한다", async () => {
    respondWith(envelope({ summary: SUMMARY }));

    await requestSessionSummary("a/b?c=d", "token");

    const [url] = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(String(url)).toContain("/sessions/a%2Fb%3Fc%3Dd/reports/summary");
  });

  it("404는 상태를 담아 던진다 — 훅이 '아직 준비 전'으로 읽는다", async () => {
    respondWith({}, 404);

    await expect(requestSessionSummary("s1", "token")).rejects.toMatchObject({
      name: "SessionSummaryError",
      status: 404,
    });
  });

  it("403은 상태를 담아 던진다", async () => {
    respondWith({}, 403);

    await expect(requestSessionSummary("s1", "token")).rejects.toMatchObject({ status: 403 });
  });

  it("응답을 받지 못하면 상태 0으로 올려 403·404와 구분한다", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new Error("offline")));

    await expect(requestSessionSummary("s1", "token")).rejects.toMatchObject({ status: 0 });
  });

  it("봉투가 깨졌으면 계약 위반으로 던진다", async () => {
    respondWith({ isSuccess: false, data: { summary: SUMMARY } });

    await expect(requestSessionSummary("s1", "token")).rejects.toBeInstanceOf(SessionSummaryError);
  });

  it("요약이 빈 문자열이거나 문자열이 아니면 던진다 — 빈 카드를 그리지 않는다", async () => {
    respondWith(envelope({ summary: "" }));
    await expect(requestSessionSummary("s1", "token")).rejects.toBeInstanceOf(SessionSummaryError);

    respondWith(envelope({ summary: 42 }));
    await expect(requestSessionSummary("s1", "token")).rejects.toBeInstanceOf(SessionSummaryError);

    respondWith(envelope({}));
    await expect(requestSessionSummary("s1", "token")).rejects.toBeInstanceOf(SessionSummaryError);
  });
});
