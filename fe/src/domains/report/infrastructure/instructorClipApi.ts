/**
 * 강사 수업 클립(공통 녹화 · 실명 화자 전사) 조회 어댑터.
 *
 * <p>`GET /api/v1/sessions/{sessionId}/reports/instructor/clip`(308). 학생 복습 클립과 **같은 계약**
 * (`recordingUrl` · `durationSeconds` · `transcript` · `seekTimestamp`, 시간은 전부 초)을 강사
 * 전용 경로로 받는다 — 학생 경로(`/reports/student`)를 강사가 부르면 403 만 받는다. 응답의
 * 모양과 실패 어휘가 같아서 타입은 `studentClipApi.ts` 의 것을 그대로 쓴다. 클립 패널
 * 하나가 requester 만 갈아 끼워 두 역할을 그리는 구조라, 여기서 모양을 다시 정의하면
 * 두 계약이 조용히 어긋날 자리만 생긴다.
 *
 * <p>학생 어댑터와 달리 이 경로는 재생 정보만 준다. 활동 집계·참여 요약 같은 리포트 본문은
 * 강사 리포트(`GET /reports/instructor`)가 따로 맡는다.
 *
 * <p>`recordingUrl` 은 권한 검증을 거친 **단기 접근 URL**이다(EC2 로컬 서빙, FRD §15.2).
 * `<video>` 는 Authorization 헤더를 싣지 못하므로 토큰이 URL 에 담겨 온다. 만료되면 화면이
 * video `error` 를 신호로 이 어댑터를 다시 불러 새 URL 을 받는다(재발급). 값이 없으면
 * `null` — 최종 MP4 병합이 아직이라는 뜻이고 오류가 아니다.
 */

import {
  StudentClipError,
  type StudentClip,
  type StudentClipRequester,
  type TranscriptSegment,
} from "./studentClipApi";

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

const isFiniteNumber = (value: unknown): value is number =>
  typeof value === "number" && Number.isFinite(value);

const objectOf = (value: unknown): Record<string, unknown> | null =>
  typeof value === "object" && value !== null ? (value as Record<string, unknown>) : null;

const arrayOf = (value: unknown): unknown[] => (Array.isArray(value) ? value : []);

/**
 * 깨진 세그먼트만 버린다. 전사 전체를 버리지 않는다 — 행 하나가 깨졌다고 수업 전체 전사를
 * 못 보여줄 이유가 없다. `text` 없는 행은 눌러도 보여줄 것이 없으므로 버린다.
 */
const parseTranscriptSegment = (value: unknown): TranscriptSegment | null => {
  const segment = objectOf(value);
  if (
    segment === null ||
    !isFiniteNumber(segment.startSeconds) ||
    typeof segment.text !== "string" ||
    segment.text.length === 0
  ) {
    return null;
  }

  return {
    startSeconds: segment.startSeconds,
    // 끝 시각이 없으면 시작 시각으로 둔다. 커서 계산은 시작 시각만 쓰므로 화면이 깨지지 않는다.
    endSeconds: isFiniteNumber(segment.endSeconds) ? segment.endSeconds : segment.startSeconds,
    speakerName: typeof segment.speakerName === "string" ? segment.speakerName : "",
    text: segment.text,
  };
};

/**
 * 봉투를 열어 `data` 객체를 꺼낸다. `isSuccess` 가 참이 아니거나 `data` 가 객체가 아니면
 * 계약 위반이므로 던진다.
 */
const dataOf = (envelope: unknown, status: number): Record<string, unknown> => {
  if (
    typeof envelope !== "object" ||
    envelope === null ||
    (envelope as { isSuccess?: unknown }).isSuccess !== true ||
    typeof (envelope as { data?: unknown }).data !== "object" ||
    (envelope as { data?: unknown }).data === null
  ) {
    throw new InstructorClipError("Instructor clip response had an invalid envelope.", status);
  }

  return (envelope as { data: Record<string, unknown> }).data;
};

/**
 * 서버는 Access Token 으로 요청자가 이 세션의 강사인지 판단한다. 비참여자·학생 호출은 403,
 * 공통 리포트가 아직 게시 전이면 404, 진행 중인 세션이면 409 가 온다. 상태 분기는 훅이 맡는다.
 */
export const requestInstructorClip: StudentClipRequester = async (sessionId, accessToken, signal) => {
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

  const data = dataOf(envelope, response.status);

  const recordingUrl =
    typeof data.recordingUrl === "string" && data.recordingUrl.length > 0
      ? data.recordingUrl
      : null;

  // 커서(현재 구간) 계산이 정렬을 전제하므로 여기서 한 번 보장한다. 서버가 정렬해 보내는 것이
  // 계약이지만, 순서가 어긋난 응답이 재생 중 하이라이트를 엉뚱한 행으로 보내면 안 된다.
  const transcript = arrayOf(data.transcript)
    .map(parseTranscriptSegment)
    .filter((segment): segment is TranscriptSegment => segment !== null)
    .sort((a, b) => a.startSeconds - b.startSeconds);

  const clip: StudentClip = {
    recordingUrl,
    durationSeconds:
      isFiniteNumber(data.durationSeconds) && data.durationSeconds >= 0 ? data.durationSeconds : 0,
    transcript,
    seekTimestamp:
      isFiniteNumber(data.seekTimestamp) && data.seekTimestamp >= 0 ? data.seekTimestamp : 0,
  };
  return clip;
};
