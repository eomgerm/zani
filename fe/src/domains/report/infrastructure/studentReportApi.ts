/**
 * 학생 학습 리포트(활동 집계 · 참여 요약 · 복습 추천) 조회 어댑터.
 *
 * <p>`GET /api/v1/sessions/{sessionId}/reports/student`(112). 서버 스펙이 확정되면
 * `npm run generate:types` 로 타입을 재생성해 이 수기 타입을 대체한다(231 어댑터와 같은 방식).
 *
 * <p>같은 경로를 읽는 `studentClipApi.ts`(복습 클립의 녹화·전사)와는 **다른 계약**이다. 서버가
 * 이 경로에 주는 것은 여기 적힌 계약뿐이고, 녹화·전사 엔드포인트는 아직 없다. 한 파일에 두
 * 계약을 담으면 어느 필드가 어느 화면의 것인지 알 수 없게 되므로 갈라 둔다.
 *
 * <p>시간은 전부 **초 단위**다(참여도 타임라인과 같은 척도). 서버 저장소가 ms(`started_offset_ms`)
 * 여도 응답에서 초로 변환해 내려주는 계약이다.
 *
 * <p>`recommendations` 는 근거가 있을 때만 0~5개다(REPORT-S-002). 없음은 빈 배열이지 오류가
 * 아니다(REPORT-S-005). 서버가 `priority` 순으로 정렬해 다섯 개까지 잘라 주므로 화면은 받은
 * 순서를 그대로 쓴다 — `priority` 값 자체는 쓰지 않아 읽지 않는다.
 *
 * <p>`activity` 는 본인 집계다. 다른 학생과 견주는 값이 아니며 하나의 점수로 합치지 않는다
 * (REPORT-S-010).
 */

/** `description` 은 서버 `review_recommendations.description` 이다(근거 문장). */
export type StudentRecommendation = {
  /**
   * 근거 유형. 249 가 다섯 가지로 확정했다 — `CONFUSED`·`MISSED`·`NO_RESPONSE`·
   * `LOW_ENGAGEMENT`·`QUESTION`. 모르는 값이어도 버리지 않는다 — 라벨링은 화면이 정한다.
   */
  readonly recommendationType: string;
  readonly title: string;
  readonly description: string;
  readonly startSeconds: number;
  readonly endSeconds: number;
};

export type StudentReport = {
  readonly activity: {
    readonly publicChatCount: number;
    readonly confusedCount: number;
    readonly missedCount: number;
    /**
     * AI 가 공개 채팅에서 질문인 발화만 세어 판단한 질문 수. 판정이 없으면 `null` 이며 0 이
     * 아니다 — 0 은 질문을 안 했다는 뜻이라 "판정이 없다"와 다르다.
     *
     * <p>앞의 셋과 달리 서버가 행을 센 값이 아니다. 공개 채팅에는 질문만 있지 않아서
     * ("감사합니다", "네") 행을 세면 그것까지 질문이 된다.
     */
    readonly questionCount: number | null;
  };
  readonly participationSummary: string;
  readonly recommendations: readonly StudentRecommendation[];
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

/** 횟수는 0 이상 정수다. 음수·소수·NaN 이 오면 "3.9회"·"-2회" 가 화면에 나가므로 낮춘다. */
const countOf = (value: unknown): number =>
  isFiniteNumber(value) && value > 0 ? Math.trunc(value) : 0;

/**
 * 판정이 있을 수도 없을 수도 있는 횟수. 없음(`null`)과 0 을 가른다 — 여기서 0 은 모델이 "질문이
 * 없었다"고 판단한 값이라 버리면 안 된다. 숫자가 아니거나 음수면 판정으로 볼 수 없어 `null` 이다.
 */
const countOrNull = (value: unknown): number | null =>
  isFiniteNumber(value) && value >= 0 ? Math.trunc(value) : null;

/**
 * 깨진 추천만 버린다. 목록 전체를 버리지 않는다 — 하나가 깨졌다고 나머지 근거 있는 추천을
 * 못 보여줄 이유가 없다. 제목과 시작 시각이 없으면 눌러도 갈 곳이 없으므로 버린다.
 */
const parseRecommendation = (value: unknown): StudentRecommendation | null => {
  const recommendation = objectOf(value);
  if (
    recommendation === null ||
    typeof recommendation.title !== "string" ||
    recommendation.title.length === 0 ||
    !isFiniteNumber(recommendation.startSeconds)
  ) {
    return null;
  }

  return {
    recommendationType:
      typeof recommendation.recommendationType === "string"
        ? recommendation.recommendationType
        : "",
    title: recommendation.title,
    description: typeof recommendation.description === "string" ? recommendation.description : "",
    startSeconds: recommendation.startSeconds,
    // 끝 시각이 없으면 시작 시각으로 둔다. 이동 목표는 시작 시각이라 화면이 깨지지 않는다.
    endSeconds: isFiniteNumber(recommendation.endSeconds)
      ? recommendation.endSeconds
      : recommendation.startSeconds,
  };
};

/**
 * 서버는 Access Token 으로 요청자가 이 세션의 학생인지 판단한다. 비참여자·학생이 아닌 호출은
 * 403, 게시된 리포트가 없으면 404, 아직 진행 중인 세션이면 409 가 온다. 상태 분기는 훅이 맡는다.
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
    // 응답을 받지 못했다. 상태 0 으로 올려 403·404·409 와 구분할 수 있게 한다.
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

  const wrapper = objectOf(envelope);
  const data = objectOf(wrapper?.data);
  if (wrapper?.isSuccess !== true || data === null) {
    throw new StudentReportError(
      "Student report response had an invalid envelope.",
      response.status,
    );
  }

  const activity = objectOf(data.activity);

  return {
    activity: {
      publicChatCount: countOf(activity?.publicChatCount),
      confusedCount: countOf(activity?.confusedCount),
      missedCount: countOf(activity?.missedCount),
      questionCount: countOrNull(activity?.questionCount),
    },
    // 요약이 비어도 집계와 추천은 살린다. 한 필드 때문에 카드 셋을 함께 잃지 않는다.
    participationSummary:
      typeof data.participationSummary === "string" ? data.participationSummary : "",
    recommendations: arrayOf(data.recommendations)
      .map(parseRecommendation)
      .filter((recommendation): recommendation is StudentRecommendation => recommendation !== null)
      // 0~5 계약(REPORT-S-002). 초과분을 그리면 "근거 있는 것만 고른다"는 약속이 깨져 보인다.
      .slice(0, 5),
  };
};
