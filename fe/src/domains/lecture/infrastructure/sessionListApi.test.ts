import { afterEach, describe, expect, it, vi } from "vitest";

import {
  requestSessionIdentities,
  requestSessionList,
  SessionListRequestError,
} from "./sessionListApi";

const full = {
  sessionId: "100",
  inviteCode: "AB12CD34",
  title: "자료구조 3주차",
  instructorName: "박서준",
  status: "LIVE",
  role: "STUDENT",
  startedAt: "2026-08-03T09:00:00Z",
  endedAt: null,
  participantCount: 12,
  reportStatus: "NONE",
  rejoinable: true,
};

const respondWith = (data: unknown, status = 200) => {
  vi.stubGlobal(
    "fetch",
    vi.fn(async () => ({
      ok: status >= 200 && status < 300,
      status,
      json: async () => ({ isSuccess: true, data }),
    })),
  );
};

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("requestSessionList", () => {
  it("계약대로 온 목록을 읽는다", async () => {
    respondWith([full]);

    const sessions = await requestSessionList("token");

    expect(sessions).toHaveLength(1);
    expect(sessions[0].title).toBe("자료구조 3주차");
  });

  /**
   * 일부만 받아 두면 화면이 빈 칸을 그리고, 원인이 서버 변경인지 이쪽 버그인지 나중에 가릴 수 없다.
   */
  it("목록이 쓰는 필드가 빠지면 전체를 거절한다", async () => {
    const withoutTitle: Record<string, unknown> = { ...full };
    delete withoutTitle.title;
    respondWith([withoutTitle]);

    await expect(requestSessionList("token")).rejects.toBeInstanceOf(SessionListRequestError);
  });

  /** TSID 를 숫자로 내보내는 구성도 있어 둘 다 받는다. */
  it("숫자로 온 ID 도 문자열로 통일한다", async () => {
    respondWith([{ ...full, sessionId: 12345 }]);

    const sessions = await requestSessionList("token");

    expect(sessions[0].sessionId).toBe("12345");
  });
});

describe("requestSessionIdentities", () => {
  /**
   * 이 분리의 이유다. 목록 화면이 요구하는 엄격함을 식별자만 쓰는 쪽까지 떠안으면, 서버가 리포트 필드 하나를
   * 바꿨을 때 "진행 중인 수업으로 돌아가기" 까지 막힌다.
   */
  it("목록 전용 필드가 없어도 식별 정보는 읽는다", async () => {
    respondWith([
      {
        sessionId: "100",
        inviteCode: "AB12CD34",
        status: "LIVE",
        role: "INSTRUCTOR",
      },
    ]);

    const sessions = await requestSessionIdentities("token");

    expect(sessions[0].sessionId).toBe("100");
    expect(sessions[0].inviteCode).toBe("AB12CD34");
  });

  /** 배너도 어느 수업인지는 알아야 한다. 식별에 필요한 것까지 빠지면 그때는 거절한다. */
  it("식별에 필요한 필드가 빠지면 거절한다", async () => {
    respondWith([{ inviteCode: "AB12CD34", status: "LIVE", role: "INSTRUCTOR" }]);

    await expect(requestSessionIdentities("token")).rejects.toBeInstanceOf(
      SessionListRequestError,
    );
  });

  it("인증이 거절되면 상태를 실은 오류를 낸다", async () => {
    respondWith(null, 401);

    await expect(requestSessionIdentities("token")).rejects.toMatchObject({ status: 401 });
  });
});
