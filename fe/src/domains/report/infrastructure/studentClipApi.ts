/**
 * 복습 클립(공통 녹화 · 실명 화자 전사) 조회 어댑터.
 *
 * <p>서버 스펙이 확정되면 `npm run generate:types` 로 타입을 재생성해 이 수기 타입을 대체한다
 * (231 어댑터와 같은 방식). 그전까지의 계약: 시간은 전부 **초 단위**다(참여도 타임라인과 같은
 * 척도). 서버 저장소가 ms(`started_offset_ms`)여도 응답에서 초로 변환해 내려주는 계약이다.
 *
 * <p><b>이 어댑터가 부르는 `GET /api/v1/sessions/{sessionId}/reports/student` 는 아직 이 계약을
 * 주지 않는다.</b> 112 가 그 경로를 학습 리포트(활동 집계 · 참여 요약 · 복습 추천)로 확정했고
 * 녹화·전사를 주는 엔드포인트는 아직 없다 — `studentReportApi.ts` 가 그 학습 리포트 계약이다.
 * 그래서 지금 이 어댑터는 `recordingUrl: null`·빈 전사를 받아 "녹화 없음" 으로 떨어진다. 경로를
 * 옮기는 일은 서버가 녹화·전사 엔드포인트를 낼 때 함께 정한다.
 *
 * <p>`recordingUrl` 은 권한 검증을 거친 **단기 접근 URL**이다(EC2 로컬 서빙, FRD §15.2).
 * `<video>` 는 Authorization 헤더를 싣지 못하므로 토큰이 URL 에 담겨 온다. 만료되면 미디어
 * 요청이 401 로 죽는데 상태코드는 JS 에 보이지 않는다 — 화면은 video `error` 를 신호로 이
 * 어댑터를 다시 불러 새 URL 을 받는다(재발급). 값이 없으면 `null` — 녹화가 아직 없다는 뜻이다.
 *
 * <p>`transcript` 의 화자는 **실명 표시 이름**이다(REPORT-S-001). 저장소의 화자 키
 * (`sessionParticipantId`)를 실명으로 푸는 일은 서버가 조립 시점에 끝낸다 — 화면은 매핑하지
 * 않는다. 익명 별칭(`student-001`)이 화면에 보이면 계약 위반이다.
 *
 * <p>`seekTimestamp` 는 초기 재생 위치(초)다. 딥링크 진입 위치로만 쓴다 — 복습 추천 각각의 이동
 * 목표는 학습 리포트가 주는 자기 `startSeconds` 가 맡는다(REPORT-S-004). 없거나 음수면 0.
 */

export type TranscriptSegment = {
  readonly startSeconds: number;
  readonly endSeconds: number;
  readonly speakerName: string;
  readonly text: string;
};

export type StudentClip = {
  readonly recordingUrl: string | null;
  readonly durationSeconds: number;
  readonly transcript: readonly TranscriptSegment[];
  readonly seekTimestamp: number;
};

export class StudentClipError extends Error {
  /** HTTP 상태. 응답을 받지 못했으면 0. */
  readonly status: number;

  constructor(message: string, status: number) {
    super(message);
    this.name = "StudentClipError";
    this.status = status;
  }
}

export type StudentClipRequester = (
  sessionId: string,
  accessToken: string,
  signal?: AbortSignal,
) => Promise<StudentClip>;

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
 * 봉투를 열어 `data` 객체를 꺼낸다. `isSuccess` 가 참이 아니거나 `data` 가 객체가 아니면 계약
 * 위반이라 `null` 이다 — 무엇을 던질지는 어댑터가 자기 실패 어휘로 정한다(강사 어댑터도 쓴다).
 */
export const clipEnvelopeData = (envelope: unknown): Record<string, unknown> | null => {
  if (
    typeof envelope !== "object" ||
    envelope === null ||
    (envelope as { isSuccess?: unknown }).isSuccess !== true ||
    typeof (envelope as { data?: unknown }).data !== "object" ||
    (envelope as { data?: unknown }).data === null
  ) {
    return null;
  }

  return (envelope as { data: Record<string, unknown> }).data;
};

/**
 * 클립 응답 본문을 계약 모양으로 옮긴다. 학생 복습 클립과 강사 수업 클립(308)이 같은 계약을
 * 읽으므로 결측 처리·정렬·범위 낮춤 규칙을 이 함수 하나가 소유한다 — 두 곳에 두면 한쪽만
 * 고쳐진다(BE 의 SessionTranscriptQuery 와 같은 선택).
 */
export const parseClip = (data: Record<string, unknown>): StudentClip => {
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

  return {
    recordingUrl,
    durationSeconds:
      isFiniteNumber(data.durationSeconds) && data.durationSeconds >= 0 ? data.durationSeconds : 0,
    transcript,
    seekTimestamp:
      isFiniteNumber(data.seekTimestamp) && data.seekTimestamp >= 0 ? data.seekTimestamp : 0,
  };
};

/**
 * 서버는 Access Token 으로 요청자가 이 세션의 학생인지 판단한다. 비참여자 접근은 403, 산출물이
 * 아직 없으면(진행 중 포함) 404 가 온다. 상태 분기는 훅이 맡는다.
 */
export const requestStudentClip: StudentClipRequester = async (sessionId, accessToken, signal) => {
  const apiBaseUrl = (process.env.NEXT_PUBLIC_API_BASE_URL ?? "").replace(/\/$/, "");

  let response: Response;
  try {
    response = await fetch(
      `${apiBaseUrl}/api/v1/sessions/${encodeURIComponent(sessionId)}/reports/student`,
      {
        method: "GET",
        headers: { Accept: "application/json", Authorization: `Bearer ${accessToken}` },
        credentials: "include",
        signal,
      },
    );
  } catch (error) {
    // 응답을 받지 못했다. 상태 0 으로 올려 403·404 와 구분할 수 있게 한다.
    throw new StudentClipError(`Student clip request failed: ${String(error)}`, 0);
  }

  if (!response.ok) {
    throw new StudentClipError(
      `Student clip request failed with status ${response.status}.`,
      response.status,
    );
  }

  let envelope: unknown;
  try {
    envelope = await response.json();
  } catch {
    throw new StudentClipError("Student clip response was not valid JSON.", response.status);
  }

  const data = clipEnvelopeData(envelope);
  if (data === null) {
    throw new StudentClipError("Student clip response had an invalid envelope.", response.status);
  }

  return parseClip(data);
};
