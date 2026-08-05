import type { SessionSummary } from "../infrastructure/sessionListApi";

/**
 * 카드가 보여주는 강의 상태.
 *
 * <p>서버는 수업 상태(`status`)와 리포트 처리 상태(`reportStatus`)를 따로 내린다. 카드가 칩 하나로 보여주는 값이라 여기서 하나로 접는다.
 */
export type LectureStatus = "LIVE" | "PROCESSING" | "COMPLETED" | "FAILED";

export interface MyLecture {
  id: string;
  title: string;
  /** `YYYY-MM-DD`. 달력이 이 문자열의 앞자리로 월을 가른다. */
  date: string;
  /** 시작 시각 ISO 8601 원본. 리포트 헤더가 요일과 시:분까지 적어야 해서 날짜만으로는 모자란다. */
  startedAt: string;
  role: "instructor" | "student";
  status: LectureStatus;
  /** 사람이 읽는 진행 시간. 진행 중이면 "진행 중". */
  dur: string;
  /** 들어온 적 있는 사람 수. 진행강의 카드에서 쓴다. */
  students: number;
  instructor: string;
  /** 프리조인을 다시 거치지 않고 강의실로 바로 들어갈 수 있는지. */
  rejoinable: boolean;
}

/**
 * 수업 상태와 리포트 상태를 카드용 한 값으로 접는다.
 *
 * <p>진행 중이면 리포트는 아직 의미가 없다 — 수업이 끝나야 사후 처리가 시작되므로 `LIVE` 가 우선한다.
 *
 * <p>`NONE`(강사가 메모를 확정하지 않아 처리가 시작된 적 없음)은 `PROCESSING` 으로 접는다. 화면에서는 둘 다 "아직 기다려야 함"이고, 학생에게 "강사가 메모를 안 썼다"는 사정을 알릴
 * 이유도 없다.
 */
export function foldStatus(status: string, reportStatus: string): LectureStatus {
  if (status === "LIVE") return "LIVE";
  if (reportStatus === "COMPLETED") return "COMPLETED";
  if (reportStatus === "FAILED") return "FAILED";
  return "PROCESSING";
}

/** `2026-08-03T09:00:00Z` → `2026-08-03`. 달력이 로컬 날짜 기준으로 묶으므로 UTC 문자열을 그대로 자르지 않는다. */
function localDate(iso: string): string {
  const at = new Date(iso);
  if (Number.isNaN(at.getTime())) return "";
  const month = `${at.getMonth() + 1}`.padStart(2, "0");
  const day = `${at.getDate()}`.padStart(2, "0");
  return `${at.getFullYear()}-${month}-${day}`;
}

/** 끝나지 않았으면 길이를 알 수 없다. 0분으로 보이면 "짧게 끝난 수업"으로 오해하므로 진행 중임을 그대로 쓴다. */
function duration(startedAt: string, endedAt: string | null): string {
  if (endedAt === null) return "진행 중";
  const from = new Date(startedAt).getTime();
  const to = new Date(endedAt).getTime();
  if (Number.isNaN(from) || Number.isNaN(to) || to <= from) return "-";

  const totalMinutes = Math.round((to - from) / 60_000);
  const hours = Math.floor(totalMinutes / 60);
  const minutes = totalMinutes % 60;
  if (hours === 0) return `${minutes}분`;
  if (minutes === 0) return `${hours}시간`;
  return `${hours}시간 ${minutes}분`;
}

export function formatSessionStartedAt(startedAt: string): string {
  const at = new Date(startedAt);
  if (Number.isNaN(at.getTime())) return "-";
  const month = `${at.getMonth() + 1}`.padStart(2, "0");
  const day = `${at.getDate()}`.padStart(2, "0");
  const hour = `${at.getHours()}`.padStart(2, "0");
  const minute = `${at.getMinutes()}`.padStart(2, "0");
  const weekday = ["일", "월", "화", "수", "목", "금", "토"][at.getDay()];
  return `${at.getFullYear()}.${month}.${day} (${weekday}) ${hour}:${minute}`;
}

export function toMyLecture(summary: SessionSummary): MyLecture {
  return {
    id: summary.sessionId,
    title: summary.title,
    date: localDate(summary.startedAt),
    startedAt: summary.startedAt,
    role: summary.role === "INSTRUCTOR" ? "instructor" : "student",
    status: foldStatus(summary.status, summary.reportStatus),
    dur: duration(summary.startedAt, summary.endedAt),
    students: summary.participantCount,
    instructor: summary.instructorName,
    rejoinable: summary.rejoinable,
  };
}
