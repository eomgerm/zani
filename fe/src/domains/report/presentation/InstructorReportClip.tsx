"use client";

import { requestInstructorClip } from "../infrastructure/instructorClipApi";
import { StudentReportClip, type StudentReportClipProps } from "./StudentReportClip";

/**
 * 강사 수업 클립 패널 — 복습 클립과 같은 패널을 강사 엔드포인트로 조회한다(S15P11A105-308).
 *
 * <p>플레이어·전사·상태 안내의 배선은 역할과 무관해서 패널을 새로 그리지 않고 requester 만
 * 갈아 끼운다. 두 벌로 나누면 이동 명령 합류(nonce)나 URL 재발급 같은 규칙이 한쪽만 고쳐진다.
 *
 * <p>기본 어댑터를 여기서 물리는 것이 이 컴포넌트의 존재 이유다. 도메인 밖(lecture 의 클립 탭)은
 * 어댑터를 볼 수 없으므로(도메인 공개 API 규칙), 어느 경로를 부를지는 이 안에서 정해져야 한다.
 */
export function InstructorReportClip({
  request = requestInstructorClip,
  ...rest
}: StudentReportClipProps) {
  return <StudentReportClip {...rest} request={request} />;
}
