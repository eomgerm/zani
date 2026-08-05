/**
 * AI 이해도 퀴즈 조회·제출 어댑터.
 *
 * <p>`GET /api/v1/sessions/{sessionId}/quiz` — 문항과 보기. 제출 전에는 문항의 `grading` 이 없다.
 * <p>`POST /api/v1/sessions/{sessionId}/quiz/answers` — 답안 일괄 제출. 모든 문항을 정확히 한 번씩
 * 담아야 하고, 제출 후에는 바꿀 수 없다. 그래서 화면은 문항을 오가며 고르되 전송은 한 번만 한다.
 *
 * <p>상태: 조회는 403(이 세션의 학생이 아님) · 404(아직 생성 전 — 진행 중 수업도 여기로 온다),
 * 제출은 400(답안이 계약과 다름) · 409(이미 제출됨)가 더 붙는다.
 *
 * <p><b>식별자는 문자열로만 다룬다.</b> `quizId`·`questionId`·`optionId` 는 TSID(64비트)라 JS 의
 * 안전 정수 범위(2^53)를 넘는다. `JSON.parse` 를 그냥 쓰면 파싱하는 순간 끝자리가 뭉개지고, 그
 * 망가진 `optionId` 를 제출하면 학생이 고르지 않은 보기로 채점된다. 그래서 본문을 글자로 받아
 * 긴 정수를 문자열로 감싼 뒤에 파싱한다.
 */

export type QuizOption = {
  readonly optionId: string;
  readonly order: number;
  readonly text: string;
};

export type QuizGrading = {
  readonly correct: boolean;
  readonly selectedOptionId: string | null;
  readonly correctOptionId: string | null;
  readonly explanation: string;
  /**
   * 근거가 된 강의 구간의 시작 시각(초). 249 가 `sectionStartedOffsetMs` 로 채우기 시작하면
   * 값이 오고, 그전에는 `null` 이다 — 값이 없으면 화면은 "구간 다시 보기" 를 내지 않는다.
   * 갈 곳을 모르는 버튼을 두면 눌러도 아무 일이 없거나 엉뚱한 자리로 간다.
   */
  readonly sectionStartSeconds: number | null;
};

export type QuizQuestion = {
  readonly questionId: string;
  readonly order: number;
  readonly text: string;
  readonly options: readonly QuizOption[];
  /** 제출 전에는 `null`. 정답도 해설도 제출 전에는 내려오지 않는다. */
  readonly grading: QuizGrading | null;
};

export type StudentQuiz = {
  readonly title: string;
  readonly description: string;
  /** 예상 풀이 시간(분). 서버가 주지 않으면 `null` 이며 0 이 아니다. */
  readonly estimatedDurationMinutes: number | null;
  readonly submitted: boolean;
  readonly questions: readonly QuizQuestion[];
};

/** 제출할 답안 하나. 화면이 고른 보기를 그대로 담는다. */
export type QuizAnswer = {
  readonly questionId: string;
  readonly selectedOptionId: string;
};

export type QuizGradingSummary = {
  readonly totalCount: number;
  readonly correctCount: number;
  /** 문항 id → 채점. 출제 순서를 다시 맞출 필요가 없게 맵으로 접어 둔다. */
  readonly gradingByQuestionId: Readonly<Record<string, QuizGrading>>;
};

export class StudentQuizError extends Error {
  /** HTTP 상태. 응답을 받지 못했으면 0. */
  readonly status: number;

  constructor(message: string, status: number) {
    super(message);
    this.name = "StudentQuizError";
    this.status = status;
  }
}

export type StudentQuizRequester = (
  sessionId: string,
  accessToken: string,
  signal?: AbortSignal,
) => Promise<StudentQuiz>;

export type QuizAnswersSubmitter = (
  sessionId: string,
  accessToken: string,
  answers: readonly QuizAnswer[],
) => Promise<QuizGradingSummary>;

const isFiniteNumber = (value: unknown): value is number =>
  typeof value === "number" && Number.isFinite(value);

const objectOf = (value: unknown): Record<string, unknown> | null =>
  typeof value === "object" && value !== null ? (value as Record<string, unknown>) : null;

const arrayOf = (value: unknown): unknown[] => (Array.isArray(value) ? value : []);

/**
 * 값 위치에 있는 긴 정수를 문자열로 감싼다. 16자리부터 감싸는 이유는 2^53 이 16자리이기 때문이다.
 *
 * <p>콜론 바로 뒤의 따옴표 없는 숫자만 고른다. 이미 문자열인 값(`: "123…"`)과 본문 안에 숫자가
 * 섞인 문장(`: "구간 1234567890123456"`)은 앞에 따옴표가 있어 걸리지 않는다.
 */
const quoteLongIntegers = (json: string): string =>
  json.replace(/:\s*(-?\d{16,})(?=\s*[,}\]])/g, ': "$1"');

/** 식별자는 숫자로 와도 문자열로 낮춘다. 없거나 빈 값은 null 이다. */
const idOf = (value: unknown): string | null => {
  if (typeof value === "string" && value.length > 0) return value;
  if (isFiniteNumber(value)) return String(value);
  return null;
};

/** ms 를 초로 내린다. 음수·비정상은 값이 없는 것으로 본다. */
const secondsFromMs = (value: unknown): number | null =>
  isFiniteNumber(value) && value >= 0 ? Math.floor(value / 1000) : null;

const parseGrading = (value: unknown): QuizGrading | null => {
  const grading = objectOf(value);
  if (grading === null) return null;

  return {
    correct: grading.correct === true,
    selectedOptionId: idOf(grading.selectedOptionId),
    correctOptionId: idOf(grading.correctOptionId),
    explanation: typeof grading.explanation === "string" ? grading.explanation : "",
    sectionStartSeconds: secondsFromMs(grading.sectionStartedOffsetMs),
  };
};

const parseOption = (value: unknown): QuizOption | null => {
  const option = objectOf(value);
  const optionId = idOf(option?.optionId);
  if (option === null || optionId === null || typeof option.text !== "string") return null;

  return {
    optionId,
    order: isFiniteNumber(option.order) ? option.order : 0,
    text: option.text,
  };
};

/**
 * 깨진 문항은 버린다. 고를 보기가 없거나 id 가 없으면 답을 낼 수 없고, 그런 문항이 목록에 남으면
 * "모든 문항을 한 번씩" 조건을 만족하는 제출을 만들 수 없다.
 */
const parseQuestion = (value: unknown): QuizQuestion | null => {
  const question = objectOf(value);
  const questionId = idOf(question?.questionId);
  if (question === null || questionId === null || typeof question.text !== "string") return null;

  const options = arrayOf(question.options)
    .map(parseOption)
    .filter((option): option is QuizOption => option !== null);
  if (options.length === 0) return null;

  return {
    questionId,
    order: isFiniteNumber(question.order) ? question.order : 0,
    text: question.text,
    options,
    grading: parseGrading(question.grading),
  };
};

/** 봉투를 열어 `data` 를 꺼낸다. 긴 정수는 파싱 전에 문자열로 감싼다. */
const dataOf = async (response: Response, what: string): Promise<Record<string, unknown>> => {
  let raw: string;
  try {
    raw = await response.text();
  } catch {
    throw new StudentQuizError(`${what} response could not be read.`, response.status);
  }

  let envelope: unknown;
  try {
    envelope = JSON.parse(quoteLongIntegers(raw));
  } catch {
    throw new StudentQuizError(`${what} response was not valid JSON.`, response.status);
  }

  const wrapper = objectOf(envelope);
  const data = objectOf(wrapper?.data);
  if (wrapper?.isSuccess !== true || data === null) {
    throw new StudentQuizError(`${what} response had an invalid envelope.`, response.status);
  }
  return data;
};

const quizUrl = (sessionId: string, suffix = ""): string => {
  const apiBaseUrl = (process.env.NEXT_PUBLIC_API_BASE_URL ?? "").replace(/\/$/, "");
  return `${apiBaseUrl}/api/v1/sessions/${encodeURIComponent(sessionId)}/quiz${suffix}`;
};

export const requestStudentQuiz: StudentQuizRequester = async (sessionId, accessToken, signal) => {
  let response: Response;
  try {
    response = await fetch(quizUrl(sessionId), {
      method: "GET",
      headers: { Accept: "application/json", Authorization: `Bearer ${accessToken}` },
      credentials: "include",
      signal,
    });
  } catch (error) {
    // 응답을 받지 못했다. 상태 0 으로 올려 403·404 와 구분할 수 있게 한다.
    throw new StudentQuizError(`Student quiz request failed: ${String(error)}`, 0);
  }

  if (!response.ok) {
    throw new StudentQuizError(
      `Student quiz request failed with status ${response.status}.`,
      response.status,
    );
  }

  const data = await dataOf(response, "Student quiz");
  const questions = arrayOf(data.questions)
    .map(parseQuestion)
    .filter((question): question is QuizQuestion => question !== null)
    // 출제 순서는 서버가 정한다. 어긋난 응답이 와도 문항 번호와 화면 순서가 갈리지 않게 한다.
    .sort((a, b) => a.order - b.order);

  const duration = data.estimatedDurationMinutes;

  return {
    title: typeof data.title === "string" ? data.title : "",
    description: typeof data.description === "string" ? data.description : "",
    estimatedDurationMinutes:
      isFiniteNumber(duration) && duration > 0 ? Math.trunc(duration) : null,
    submitted: data.submitted === true,
    questions,
  };
};

export const submitQuizAnswers: QuizAnswersSubmitter = async (sessionId, accessToken, answers) => {
  let response: Response;
  try {
    response = await fetch(quizUrl(sessionId, "/answers"), {
      method: "POST",
      headers: {
        Accept: "application/json",
        "Content-Type": "application/json",
        Authorization: `Bearer ${accessToken}`,
      },
      credentials: "include",
      body: JSON.stringify({ answers }),
    });
  } catch (error) {
    throw new StudentQuizError(`Quiz answer submission failed: ${String(error)}`, 0);
  }

  if (!response.ok) {
    throw new StudentQuizError(
      `Quiz answer submission failed with status ${response.status}.`,
      response.status,
    );
  }

  const data = await dataOf(response, "Quiz grading");
  const gradingByQuestionId: Record<string, QuizGrading> = {};
  for (const row of arrayOf(data.results)) {
    const questionId = idOf(objectOf(row)?.questionId);
    const grading = parseGrading(row);
    if (questionId !== null && grading !== null) gradingByQuestionId[questionId] = grading;
  }

  return {
    totalCount: isFiniteNumber(data.totalCount) ? data.totalCount : answers.length,
    correctCount: isFiniteNumber(data.correctCount) ? data.correctCount : 0,
    gradingByQuestionId,
  };
};
