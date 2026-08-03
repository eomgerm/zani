import { describe, expect, it } from "vitest";

import type { SessionSummary } from "../infrastructure/sessionListApi";
import { foldStatus, toMyLecture } from "./myLectures";

const summary = (over: Partial<SessionSummary> = {}): SessionSummary => ({
  sessionId: "9876543210123456",
  inviteCode: "AB12CD34",
  title: "자료구조 3주차",
  instructorName: "박서준",
  status: "ENDED",
  role: "STUDENT",
  startedAt: "2026-08-03T09:00:00Z",
  endedAt: "2026-08-03T10:32:00Z",
  participantCount: 24,
  reportStatus: "COMPLETED",
  rejoinable: false,
  ...over,
});

describe("foldStatus", () => {
  /** 수업이 끝나야 사후 처리가 시작된다. 진행 중에 리포트 상태를 보여줄 이유가 없다. */
  it("진행 중이면 리포트 상태와 무관하게 LIVE 다", () => {
    expect(foldStatus("LIVE", "NONE")).toBe("LIVE");
    expect(foldStatus("LIVE", "COMPLETED")).toBe("LIVE");
  });

  it.each([
    ["COMPLETED", "COMPLETED"],
    ["FAILED", "FAILED"],
  ])("끝난 수업은 리포트 상태를 따른다: %s", (reportStatus, expected) => {
    expect(foldStatus("ENDED", reportStatus)).toBe(expected);
  });

  /** 화면에서는 "처리 시작 전"과 "처리 중"이 똑같이 기다리는 상태다. 학생에게 강사가 메모를 안 썼다는 사정을 알릴 이유도 없다. */
  it("NONE 은 PROCESSING 으로 접는다", () => {
    expect(foldStatus("ENDED", "NONE")).toBe("PROCESSING");
  });

  /** 서버에 단계가 새로 생겨도 화면이 빈 칩을 그리면 안 된다. */
  it("모르는 리포트 상태는 PROCESSING 으로 둔다", () => {
    expect(foldStatus("ENDED", "VALIDATING")).toBe("PROCESSING");
  });
});

describe("toMyLecture", () => {
  it("서버 역할 표기를 화면 표기로 바꾼다", () => {
    expect(toMyLecture(summary({ role: "INSTRUCTOR" })).role).toBe("instructor");
    expect(toMyLecture(summary({ role: "STUDENT" })).role).toBe("student");
  });

  it("시작 시각에서 날짜를 뽑는다", () => {
    expect(toMyLecture(summary()).date).toMatch(/^\d{4}-\d{2}-\d{2}$/);
  });

  it("진행 시간을 사람이 읽는 문구로 만든다", () => {
    expect(toMyLecture(summary()).dur).toBe("1시간 32분");
    expect(toMyLecture(summary({ endedAt: "2026-08-03T09:47:00Z" })).dur).toBe("47분");
    expect(toMyLecture(summary({ endedAt: "2026-08-03T11:00:00Z" })).dur).toBe("2시간");
  });

  /** 0분으로 보이면 "짧게 끝난 수업"으로 오해한다. 끝나지 않았다는 사실을 그대로 쓴다. */
  it("끝나지 않았으면 진행 중이라고 쓴다", () => {
    expect(toMyLecture(summary({ status: "LIVE", endedAt: null })).dur).toBe("진행 중");
  });

  it("참가자 수와 강사 이름을 그대로 옮긴다", () => {
    const lecture = toMyLecture(summary());
    expect(lecture.students).toBe(24);
    expect(lecture.instructor).toBe("박서준");
  });

  /** TSID 는 JS 안전 정수를 넘는다. 숫자로 다루면 값이 반올림된다. */
  it("세션 ID 를 문자열 그대로 쓴다", () => {
    expect(toMyLecture(summary()).id).toBe("9876543210123456");
  });
});
