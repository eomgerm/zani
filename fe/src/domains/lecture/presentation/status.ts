import { color } from "@/shared/lib/theme";
import type { LectureStatus } from "./fixtures";

/** 강의 상태별 뱃지 라벨/색 정보 (강의 카드 상태 칩) */
export function statusInfo(status: LectureStatus): {
  label: string;
  bg: string;
  fg: string;
  dot: string;
} {
  switch (status) {
    case "LIVE":
      return { label: "진행 중", bg: color.redSoft, fg: color.red, dot: color.red };
    case "PROCESSING":
      return { label: "분석 중", bg: "#eef4ff", fg: "#4a6fd6", dot: "#4a6fd6" };
    case "COMPLETED":
      return { label: "완료", bg: color.primarySoft, fg: color.primaryDeep, dot: color.primary };
    case "FAILED":
      return { label: "실패", bg: "#fdeeee", fg: color.red, dot: color.red };
  }
}
