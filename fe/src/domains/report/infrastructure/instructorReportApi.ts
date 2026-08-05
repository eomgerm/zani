/**
 * 강사 리포트(종합 피드백·분야별 평가·인사이트·개선 팁·한눈에 보기 집계) 조회 어댑터.
 *
 * <p>`GET /api/v1/sessions/{sessionId}/reports/instructor` (109).
 *
 * <p><b>집중 흐름과 수업 내용 구간은 여기서 읽지 않는다.</b> 응답에 `sections` 가 들어 있지만
 * 화면은 그 값을 쓰지 않는다 — 집중 흐름 카드가 `GET /reports/attention/group` 에서 같은 구간을
 * 구간별 집중 단계까지 붙여 받아 이미 그리고 있다. 같은 값을 두 곳에서 읽으면 어느 쪽이 최신인지
 * 화면 안에서 갈린다.
 *
 * <p><b>시간은 초 단위로 바꿔 내보낸다.</b> 서버는 ms(`startedOffsetMs`)로 주지만 화면의 이동
 * 요청(`onJumpToClip`)과 참여도 타임라인이 전부 초를 쓴다. 척도를 컴포넌트마다 다르게 두면
 * 1000 배 어긋난 자리로 보내는 버그가 조용히 생긴다.
 *
 * <p><b>학생 식별자는 어떤 필드로도 오지 않는다</b>(REPORT-I-002). 서버가 내려보내지 않고 이
 * 어댑터도 읽지 않는다. 필드를 늘릴 때 이 문장을 먼저 읽어라 — 강사 화면에서 개인을 짚어낼 수
 * 있게 되면 참여도 측정이 감시로 바뀐다.
 */

/** 분야별 평가 한 항목. `evaluationType` 은 서버 enum 문자열이고 라벨링은 화면이 정한다. */
export type InstructorScore = {
  readonly evaluationType: string;
  /** 0~100. 퍼센트가 아니라 점수다. */
  readonly score: number;
};

/** 수업 인사이트 한 항목(관찰). 수업 전체를 가리키면 구간이 `null` 이다. */
export type InstructorInsight = {
  readonly insightType: string;
  readonly content: string;
  readonly startSeconds: number | null;
  readonly endSeconds: number | null;
};

/** 개선 팁 한 항목(해 볼 것). */
export type InstructorTip = {
  readonly tipType: string;
  readonly title: string;
  readonly content: string;
};

export type InstructorReportStats = {
  readonly studentCount: number;
  readonly durationSeconds: number;
  /**
   * 모델이 판단한 질문 수의 합. 채팅 행 수가 아니다.
   *
   * <p>분석이 값을 내지 못했으면 `null` 이며 **0 이 아니다**. "아무도 질문하지 않았다" 와
   * "아직 셀 수 없다" 는 화면에서 다르게 보여야 한다.
   */
  readonly questionCount: number | null;
  readonly alertCount: number;
};

export type InstructorReport = {
  readonly overallFeedback: string;
  readonly stats: InstructorReportStats;
  readonly scores: readonly InstructorScore[];
  readonly insights: readonly InstructorInsight[];
  readonly tips: readonly InstructorTip[];
};

export class InstructorReportError extends Error {
  /** HTTP 상태. 응답을 받지 못했으면 0. */
  readonly status: number;

  constructor(message: string, status: number) {
    super(message);
    this.name = "InstructorReportError";
    this.status = status;
  }
}

export type InstructorReportRequester = (
  sessionId: string,
  accessToken: string,
  signal?: AbortSignal,
) => Promise<InstructorReport>;

const isFiniteNumber = (value: unknown): value is number =>
  typeof value === "number" && Number.isFinite(value);

const objectOf = (value: unknown): Record<string, unknown> | null =>
  typeof value === "object" && value !== null ? (value as Record<string, unknown>) : null;

const arrayOf = (value: unknown): unknown[] => (Array.isArray(value) ? value : []);

const stringOf = (value: unknown): string => (typeof value === "string" ? value : "");

/** 음이 아닌 정수만 받는다. 인원·횟수에 음수나 소수가 오면 계약 위반이라 0 으로 읽는다. */
const countOf = (value: unknown): number =>
  isFiniteNumber(value) && value >= 0 ? Math.floor(value) : 0;

/** ms 를 초로. 수업 전체를 가리키는 항목은 `null` 이고, 그 뜻을 0 으로 뭉개지 않는다. */
const secondsOf = (value: unknown): number | null =>
  isFiniteNumber(value) && value >= 0 ? Math.round(value / 1000) : null;

/**
 * 깨진 항목만 버린다. 목록 전체를 버리지 않는다 — 평가 하나가 깨졌다고 나머지 셋까지 감출
 * 이유가 없다. 점수는 0~100 밖으로 나가면 도넛이 넘치므로 잘라 둔다.
 */
const parseScore = (value: unknown): InstructorScore | null => {
  const score = objectOf(value);
  if (score === null || !isFiniteNumber(score.score)) return null;

  const type = stringOf(score.evaluationType);
  if (type.length === 0) return null;

  return { evaluationType: type, score: Math.min(100, Math.max(0, Math.round(score.score))) };
};

/** 내용이 없는 인사이트는 눌러도 읽을 것이 없으므로 버린다. */
const parseInsight = (value: unknown): InstructorInsight | null => {
  const insight = objectOf(value);
  if (insight === null) return null;

  const content = stringOf(insight.content);
  if (content.length === 0) return null;

  return {
    insightType: stringOf(insight.insightType),
    content,
    startSeconds: secondsOf(insight.startedOffsetMs),
    endSeconds: secondsOf(insight.endedOffsetMs),
  };
};

/** 제목과 내용 중 하나만 있어도 카드가 성립한다. 둘 다 없으면 빈 카드라 버린다. */
const parseTip = (value: unknown): InstructorTip | null => {
  const tip = objectOf(value);
  if (tip === null) return null;

  const title = stringOf(tip.title);
  const content = stringOf(tip.content);
  if (title.length === 0 && content.length === 0) return null;

  return { tipType: stringOf(tip.tipType), title, content };
};

const parseStats = (value: unknown): InstructorReportStats => {
  const stats = objectOf(value);
  if (stats === null) {
    return { studentCount: 0, durationSeconds: 0, questionCount: null, alertCount: 0 };
  }

  return {
    studentCount: countOf(stats.studentCount),
    durationSeconds: countOf(stats.durationSeconds),
    // 0 으로 접지 않는다. "질문 없음" 과 "셀 수 없음" 은 화면에서 다른 글자로 나가야 한다.
    questionCount:
      isFiniteNumber(stats.questionCount) && stats.questionCount >= 0
        ? Math.floor(stats.questionCount)
        : null,
    alertCount: countOf(stats.alertCount),
  };
};

/**
 * 봉투를 열어 `data` 객체를 꺼낸다. `isSuccess` 가 참이 아니거나 `data` 가 객체가 아니면
 * 계약 위반이므로 던진다.
 */
const dataOf = (envelope: unknown, status: number): Record<string, unknown> => {
  const outer = objectOf(envelope);
  if (outer === null || outer.isSuccess !== true) {
    throw new InstructorReportError("Instructor report response had an invalid envelope.", status);
  }

  const data = objectOf(outer.data);
  if (data === null) {
    throw new InstructorReportError("Instructor report response had no data object.", status);
  }

  return data;
};

/**
 * 서버는 Access Token 으로 요청자가 이 세션의 **강사**인지 판단한다.
 *
 * <p>비참가자·학생·없는 세션은 모두 403 이다(111 에서 확인). 없는 세션까지 403 인 것은 의도된
 * 설계다 — 404 로 갈리면 세션 id 를 훑어 존재 여부를 캐낼 수 있다. 리포트가 아직 없거나 공개 전이면
 * 404 다. 상태 분기는 훅이 맡는다.
 */
export const requestInstructorReport: InstructorReportRequester = async (
  sessionId,
  accessToken,
  signal,
) => {
  const apiBaseUrl = (process.env.NEXT_PUBLIC_API_BASE_URL ?? "").replace(/\/$/, "");

  let response: Response;
  try {
    response = await fetch(
      `${apiBaseUrl}/api/v1/sessions/${encodeURIComponent(sessionId)}/reports/instructor`,
      {
        method: "GET",
        headers: { Accept: "application/json", Authorization: `Bearer ${accessToken}` },
        credentials: "include",
        signal,
      },
    );
  } catch (error) {
    // 응답을 받지 못했다. 상태 0 으로 올려 403·404 와 구분할 수 있게 한다.
    throw new InstructorReportError(`Instructor report request failed: ${String(error)}`, 0);
  }

  if (!response.ok) {
    throw new InstructorReportError(
      `Instructor report request failed with status ${response.status}.`,
      response.status,
    );
  }

  let envelope: unknown;
  try {
    envelope = await response.json();
  } catch {
    throw new InstructorReportError(
      "Instructor report response was not valid JSON.",
      response.status,
    );
  }

  const data = dataOf(envelope, response.status);

  return {
    overallFeedback: stringOf(data.overallFeedback),
    stats: parseStats(data.stats),
    scores: arrayOf(data.scores)
      .map(parseScore)
      .filter((score): score is InstructorScore => score !== null),
    // 이른 구간부터 그린다. 수업 전체를 가리키는 항목(구간 없음)은 앞에 둔다 — 서버가 그 순서로
    // 주는 것이 계약이지만, 순서가 어긋난 응답이 카드 순서를 뒤집게 두지 않는다.
    insights: arrayOf(data.insights)
      .map(parseInsight)
      .filter((insight): insight is InstructorInsight => insight !== null)
      .sort((a, b) => (a.startSeconds ?? -1) - (b.startSeconds ?? -1)),
    tips: arrayOf(data.tips)
      .map(parseTip)
      .filter((tip): tip is InstructorTip => tip !== null),
  };
};
