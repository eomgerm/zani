/**
 * 강사 수업 클립(공통 녹화 · 실명 화자 전사) 조회 어댑터.
 *
 * <p>`GET /api/v1/sessions/{sessionId}/reports/instructor/clip`(308). 학생 복습 클립과 **같은
 * 계약**(`recordingUrl` · `durationSeconds` · `transcript` · `seekTimestamp`, 시간은 전부 초)을
 * 강사 전용 경로로 받는다 — 학생 경로(`/reports/student`)를 강사가 부르면 403 만 받는다.
 * 그래서 타입과 파싱 규칙(`clipEnvelopeData` · `parseClip`)을 `studentClipApi.ts` 와 공유하고,
 * 이 파일에 남는 것은 경로와 실패 어휘뿐이다 — 모양을 여기서 다시 정의하면 두 계약이 조용히
 * 어긋날 자리만 생긴다.
 *
 * <p>학생 어댑터와 달리 이 경로는 재생 정보만 준다. 활동 집계·참여 요약 같은 리포트 본문은
 * 강사 리포트(`GET /reports/instructor`)가 따로 맡는다. `recordingUrl` 이 단기 URL 인 사정과
 * 만료 재발급 흐름은 `studentClipApi.ts` 머리말에 적힌 그대로다.
 */

import { clipEnvelopeData, parseClip, StudentClipError, type StudentClipRequester } from "./studentClipApi";

/**
 * 훅(useStudentClip)의 상태 분기가 `instanceof StudentClipError` 로 실패를 읽으므로 그 아래에
 * 둔다 — 같은 패널이 두 어댑터를 갈아 끼우는 구조라 실패 어휘도 공유해야 한다. 이름만 갈라
 * 로그에서 어느 경로의 실패인지 보이게 한다.
 */
export class InstructorClipError extends StudentClipError {
  constructor(message: string, status: number) {
    super(message, status);
    this.name = "InstructorClipError";
  }
}

/**
 * 서버는 Access Token 으로 요청자가 이 세션의 강사인지 판단한다. 비참여자·학생 호출은 403,
 * 공통 리포트가 아직 게시 전이면 404, 진행 중인 세션이면 409 가 온다. 상태 분기는 훅이 맡는다.
 */
export const requestInstructorClip: StudentClipRequester = async (
  sessionId,
  accessToken,
  signal,
) => {
  const apiBaseUrl = (process.env.NEXT_PUBLIC_API_BASE_URL ?? "").replace(/\/$/, "");

  let response: Response;
  try {
    response = await fetch(
      `${apiBaseUrl}/api/v1/sessions/${encodeURIComponent(sessionId)}/reports/instructor/clip`,
      {
        method: "GET",
        headers: { Accept: "application/json", Authorization: `Bearer ${accessToken}` },
        credentials: "include",
        signal,
      },
    );
  } catch (error) {
    // 응답을 받지 못했다. 상태 0 으로 올려 403·404·409 와 구분할 수 있게 한다.
    throw new InstructorClipError(`Instructor clip request failed: ${String(error)}`, 0);
  }

  if (!response.ok) {
    throw new InstructorClipError(
      `Instructor clip request failed with status ${response.status}.`,
      response.status,
    );
  }

  let envelope: unknown;
  try {
    envelope = await response.json();
  } catch {
    throw new InstructorClipError("Instructor clip response was not valid JSON.", response.status);
  }

  const data = clipEnvelopeData(envelope);
  if (data === null) {
    throw new InstructorClipError(
      "Instructor clip response had an invalid envelope.",
      response.status,
    );
  }

  return parseClip(data);
};
