import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { fetchLiveState, LiveStateFetchError } from "./liveStateApi";

const ok = (data: unknown) =>
  ({
    ok: true,
    status: 200,
    json: async () => ({ isSuccess: true, data }),
  }) as Response;

const fetchMock = vi.fn();

beforeEach(() => {
  vi.stubGlobal("fetch", fetchMock);
  fetchMock.mockReset();
});

afterEach(() => {
  vi.unstubAllGlobals();
});

const snapshotData = {
  participants: [{ identity: "p-11", displayName: "김민수", role: "STUDENT" }],
  chatMessages: [
    { eventId: "5001", senderIdentity: "p-11", occurredOffsetMs: 1_000, content: "안녕하세요" },
  ],
  raisedHandIdentities: ["p-22"],
};

describe("fetchLiveState", () => {
  it("스냅샷을 읽는다", async () => {
    fetchMock.mockResolvedValue(ok(snapshotData));

    await expect(fetchLiveState("s1", "token")).resolves.toEqual(snapshotData);
  });

  /** 쿠키는 refresh 전용이라 API 인증 경로가 아니다. Bearer 헤더가 없으면 401 이다. */
  it("Access Token 을 Bearer 헤더로 보낸다", async () => {
    fetchMock.mockResolvedValue(ok(snapshotData));

    await fetchLiveState("s1", "test-token");

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toContain("/api/v1/sessions/s1/live-state");
    expect((init.headers as Record<string, string>).Authorization).toBe("Bearer test-token");
  });

  /** 한 건이 깨졌다고 이력 전체를 잃을 이유가 없다. */
  it("읽을 수 없는 항목만 버리고 나머지는 살린다", async () => {
    fetchMock.mockResolvedValue(
      ok({
        participants: [{ identity: "p-11", displayName: "김민수", role: "STUDENT" }, { identity: "" }],
        chatMessages: [
          { eventId: "5001", senderIdentity: "p-11", occurredOffsetMs: 1_000, content: "살아남음" },
          { eventId: "5002", senderIdentity: "p-11", content: "오프셋 없음" },
        ],
        raisedHandIdentities: ["p-22", "", 7],
      }),
    );

    const snapshot = await fetchLiveState("s1", "token");

    expect(snapshot.participants).toHaveLength(1);
    expect(snapshot.chatMessages).toHaveLength(1);
    expect(snapshot.raisedHandIdentities).toEqual(["p-22"]);
  });

  it("목록이 아예 없으면 빈 목록으로 둔다", async () => {
    fetchMock.mockResolvedValue(ok({}));

    await expect(fetchLiveState("s1", "token")).resolves.toEqual({
      participants: [],
      chatMessages: [],
      raisedHandIdentities: [],
    });
  });

  it("실패 상태를 그대로 담아 올린다", async () => {
    fetchMock.mockResolvedValue({ ok: false, status: 403 } as Response);

    await expect(fetchLiveState("s1", "token")).rejects.toMatchObject(
      new LiveStateFetchError("Live state failed with status 403.", 403),
    );
  });

  it("네트워크 실패는 상태 0 으로 올린다", async () => {
    fetchMock.mockRejectedValue(new Error("offline"));

    await expect(fetchLiveState("s1", "token")).rejects.toMatchObject({ status: 0 });
  });

  it("봉투 형태가 어긋나면 거절한다", async () => {
    fetchMock.mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({ isSuccess: false }),
    } as Response);

    await expect(fetchLiveState("s1", "token")).rejects.toBeInstanceOf(LiveStateFetchError);
  });
});
