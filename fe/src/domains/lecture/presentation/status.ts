import type { LectureStatus } from "./fixtures";

/**
 * 강의 상태별 뱃지 라벨/색 정보 (강의 카드 상태 칩).
 * 상태에 따라 런타임에 색이 정해지므로 클래스가 아닌 색상 값으로 다룬다.
 */
export function statusInfo(status: LectureStatus): {
  label: string;
  bg: string;
  fg: string;
  dot: string;
} {
  switch (status) {
    case "LIVE":
      return { label: "진행 중", bg: "#ffe7ea", fg: "#e0455f", dot: "#e0455f" };
    case "PROCESSING":
      return { label: "분석 중", bg: "#eef4ff", fg: "#4a6fd6", dot: "#4a6fd6" };
    case "COMPLETED":
      return { label: "완료", bg: "#e9f8f2", fg: "#16c582", dot: "#1cdd93" };
    case "FAILED":
      return { label: "실패", bg: "#fdeeee", fg: "#e0455f", dot: "#e0455f" };
  }
}
