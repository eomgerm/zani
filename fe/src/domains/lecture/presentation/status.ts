import type { LectureStatus } from "./myLectures";

/**
 * 강의 카드 상태 칩 정보.
 *
 * 칩 자체는 흰 배경 고정이고 상태는 왼쪽 점 색으로만 구분한다(프로토타입 statusPill/statusDot).
 * 라벨은 녹화가 아니라 분석 진행도를 가리킨다.
 *
 * 분석에 실패한 강의도 목록에 남는다. 리포트가 실패했다고 수업이 없었던 것은 아니고, 목록에서 사라지면 강사는 자기 수업이 없어진 것으로 본다.
 */
export function statusInfo(status: LectureStatus): { label: string; dot: string } {
  switch (status) {
    case "LIVE":
      return { label: "분석 전", dot: "#8a90b4" };
    case "PROCESSING":
      return { label: "분석 중", dot: "#e2b41b" };
    case "COMPLETED":
      return { label: "분석완료", dot: "#1ed08c" };
    case "FAILED":
      return { label: "분석 실패", dot: "#e0455f" };
  }
}

/** 리포트를 열 수 있는 상태인지. 분석 중·실패 강의는 카드를 눌러도 이동하지 않는다. */
export function isLectureOpenable(status: LectureStatus): boolean {
  return status === "COMPLETED" || status === "LIVE";
}
