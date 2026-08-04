/**
 * 학생 리포트(공통 녹화·실명 화자 전사·복습 추천) 조회 어댑터.
 *
 * <p>`GET /api/v1/sessions/{sessionId}/reports/student`. 서버 스펙이 확정되면
 * `npm run generate:types` 로 타입을 재생성해 이 수기 타입을 대체한다(231 어댑터와 같은 방식).
 * 그전까지의 계약: 시간은 전부 **초 단위**다(참여도 타임라인과 같은 척도). 서버 저장소가
 * ms(`started_offset_ms`)여도 응답에서 초로 변환해 내려주는 계약이다.
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
 * <p>`recommendations` 는 근거가 있을 때만 0~5개다(REPORT-S-002). 없음은 빈 배열이지 오류가
 * 아니다(REPORT-S-005). 5개 초과분은 계약 위반이므로 방어적으로 잘라낸다.
 *
 * <p>`seekTimestamp` 는 초기 재생 위치(초)다. 추천 각각의 이동 목표는 자기 `startSeconds` 가
 * 맡으므로(REPORT-S-004), 이 값은 딥링크 진입 위치로만 쓴다. 없거나 음수면 0.
 */

export type TranscriptSegment = {
  readonly startSeconds: number;
  readonly endSeconds: number;
  readonly speakerName: string;
  readonly text: string;
};

/** `reason` 은 서버 `review_recommendations.description` 을 내려받는 필드다(근거 문장). */
export type ReviewRecommendation = {
  /** TSID 라 JS 안전 정수 범위를 넘을 수 있다. 문자열로만 다룬다. 없으면 null. */
  readonly id: string | null;
  readonly title: string;
  readonly reason: string;
  readonly startSeconds: number;
  readonly endSeconds: number;
  /** 근거 유형(서버 enum 문자열). 모르는 값이어도 버리지 않는다 — 라벨링은 화면이 정한다. */
  readonly recommendationType: string;
};

export type StudentReport = {
  readonly recordingUrl: string | null;
  readonly durationSeconds: number;
  readonly transcript: readonly TranscriptSegment[];
  readonly recommendations: readonly ReviewRecommendation[];
  readonly seekTimestamp: number;
};

export class StudentReportError extends Error {
  /** HTTP 상태. 응답을 받지 못했으면 0. */
  readonly status: number;

  constructor(message: string, status: number) {
    super(message);
    this.name = "StudentReportError";
    this.status = status;
  }
}

export type StudentReportRequester = (
  sessionId: string,
  accessToken: string,
  signal?: AbortSignal,
) => Promise<StudentReport>;

const isFiniteNumber = (value: unknown): value is number =>
  typeof value === "number" && Number.isFinite(value);

const objectOf = (value: unknown): Record<string, unknown> | null =>
  typeof value === "object" && value !== null ? (value as Record<string, unknown>) : null;

const arrayOf = (value: unknown): unknown[] => (Array.isArray(value) ? value : []);

const idOf = (value: unknown): string | null => {
  if (typeof value === "string" && value.length > 0) return value;
  if (typeof value === "number" && Number.isFinite(value)) return String(value);
  return null;
};

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

const parseRecommendation = (value: unknown): ReviewRecommendation | null => {
  const recommendation = objectOf(value);
  if (
    recommendation === null ||
    !isFiniteNumber(recommendation.startSeconds) ||
    typeof recommendation.title !== "string" ||
    recommendation.title.length === 0
  ) {
    return null;
  }

  return {
    id: idOf(recommendation.id),
    title: recommendation.title,
    reason: typeof recommendation.reason === "string" ? recommendation.reason : "",
    startSeconds: recommendation.startSeconds,
    endSeconds: isFiniteNumber(recommendation.endSeconds)
      ? recommendation.endSeconds
      : recommendation.startSeconds,
    recommendationType:
      typeof recommendation.recommendationType === "string"
        ? recommendation.recommendationType
        : "",
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
    throw new StudentReportError("Student report response had an invalid envelope.", status);
  }

  return (envelope as { data: Record<string, unknown> }).data;
};

/**
 * 서버는 Access Token 으로 요청자가 이 세션의 학생인지 판단한다. 비참여자·타 학생 추천 접근은
 * 403, 리포트가 아직 없으면(진행 중 포함) 404 가 온다. 상태 분기는 훅이 맡는다.
 */
export const requestStudentReport: StudentReportRequester = async (
  sessionId,
  accessToken,
  signal,
) => {
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
    throw new StudentReportError(`Student report request failed: ${String(error)}`, 0);
  }

  if (!response.ok) {
    throw new StudentReportError(
      `Student report request failed with status ${response.status}.`,
      response.status,
    );
  }

  let envelope: unknown;
  try {
    envelope = await response.json();
  } catch {
    throw new StudentReportError("Student report response was not valid JSON.", response.status);
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

  const recommendations = arrayOf(data.recommendations)
    .map(parseRecommendation)
    .filter((recommendation): recommendation is ReviewRecommendation => recommendation !== null)
    // 0~5 계약(REPORT-S-002). 초과분을 그리면 "근거 있는 것만 고른다"는 약속이 깨져 보인다.
    .slice(0, 5);

  return {
    recordingUrl,
    durationSeconds: isFiniteNumber(data.durationSeconds) && data.durationSeconds >= 0
      ? data.durationSeconds
      : 0,
    transcript,
    recommendations,
    seekTimestamp:
      isFiniteNumber(data.seekTimestamp) && data.seekTimestamp >= 0 ? data.seekTimestamp : 0,
  };
};
