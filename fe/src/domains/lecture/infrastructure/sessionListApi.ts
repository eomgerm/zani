export type SessionSummary = {
  /** TSID 라 JS 안전 정수 범위를 넘는다. 문자열로만 다뤄야 값이 깨지지 않는다. */
  sessionId: string;
  inviteCode: string;
  title: string;
  /** 이 수업을 연 강사 이름. 학생 카드가 "누구 수업인지" 를 보여주는 데 쓴다. */
  instructorName: string;
  /** `PREPARING`, `LIVE`, `ENDING`, `NOTE_PENDING`, `ENDED` 중 하나. 서버 구현에 따라 앞의 두 개만 올 수도 있다. */
  status: string;
  /** 이 수업에서 내 역할. `INSTRUCTOR` 또는 `STUDENT`. */
  role: string;
  /** ISO 8601. */
  startedAt: string;
  /** 진행 중인 수업에는 없다. */
  endedAt: string | null;
  /** 들어온 적 있는 사람 수. 지금 접속 중인 인원이 아니다 — 참가자 행은 퇴장해도 남는다. */
  participantCount: number;
  /** `NONE`, `PROCESSING`, `COMPLETED`, `FAILED`. */
  reportStatus: string;
  /** 프리조인을 다시 거치지 않고 강의실로 들어갈 수 있는지. */
  rejoinable: boolean;
  /**
   * 카드 썸네일(최종 녹화 1/2 지점 프레임) 주소. 서명이 들어 있어 그대로 `<img src>` 에 넣는다.
   * 병합 전이거나 썸네일이 없으면 null. 이 필드를 모르는 서버 배포도 있어 없음(undefined)도 허용한다 —
   * 썸네일 하나 때문에 목록 전체가 거절되면 배포 순서가 계약이 된다.
   */
  thumbnailUrl?: string | null;
};

export class SessionListRequestError extends Error {
  /** HTTP 상태. 응답을 받지 못했으면 0. */
  readonly status: number;

  constructor(message: string, status: number) {
    super(message);
    this.name = "SessionListRequestError";
    this.status = status;
  }
}

export type SessionListRequester = (
  accessToken: string,
  signal?: AbortSignal,
) => Promise<SessionSummary[]>;

export type SessionIdentityRequester = (
  accessToken: string,
  signal?: AbortSignal,
) => Promise<SessionIdentity[]>;

/**
 * 서버가 TSID 를 숫자로 내보내는 구성도 있어 둘 다 받는다. 숫자로 파싱된 값은 JS 안전 정수 범위를 넘겨
 * 이미 반올림돼 있을 수 있으므로, 문자열로 오는 편이 정확하다(서버 쪽 개선 대상).
 */
const idOf = (value: unknown): string | null => {
  if (typeof value === "string" && value.length > 0) return value;
  if (typeof value === "number" && Number.isFinite(value)) return String(value);
  return null;
};

/**
 * 어느 수업인지 가리고 돌아가는 데만 필요한 부분.
 *
 * <p>홈의 "진행 중인 수업" 배너가 쓰는 범위다 — 어느 수업인지(`sessionId`), 내 수업인지(`role`), 시작했는지(`status`), 그리고 공유할 초대 코드까지.
 */
export type SessionIdentity = Pick<
  SessionSummary,
  "sessionId" | "inviteCode" | "status" | "role"
>;

const isSessionIdentity = (value: unknown): value is SessionIdentity => {
  if (typeof value !== "object" || value === null) {
    return false;
  }
  const summary = value as Record<string, unknown>;
  return (
    idOf(summary.sessionId) !== null &&
    typeof summary.inviteCode === "string" &&
    typeof summary.status === "string" &&
    typeof summary.role === "string"
  );
};

const isSessionSummary = (value: unknown): value is SessionSummary => {
  if (!isSessionIdentity(value)) {
    return false;
  }
  const summary = value as unknown as Record<string, unknown>;
  return (
    typeof summary.inviteCode === "string" &&
    typeof summary.title === "string" &&
    typeof summary.instructorName === "string" &&
    typeof summary.startedAt === "string" &&
    (summary.endedAt === null || typeof summary.endedAt === "string") &&
    typeof summary.participantCount === "number" &&
    typeof summary.reportStatus === "string" &&
    typeof summary.rejoinable === "boolean" &&
    (summary.thumbnailUrl === undefined ||
      summary.thumbnailUrl === null ||
      typeof summary.thumbnailUrl === "string")
  );
};

/**
 * 목록을 받아 와 요구한 모양인지 확인한다.
 *
 * <p><b>확인할 범위를 부르는 쪽이 정한다.</b> 같은 응답이라도 쓰는 곳마다 필요한 필드가 다르다. 목록 화면이 요구하는 엄격함을 식별자만 쓰는 쪽까지 떠안으면, 서버가 필드 하나를 바꿨을 때 관계없는
 * 기능까지 함께 멈춘다.
 */
async function fetchSessions<T extends { sessionId: string }>(
  accessToken: string,
  signal: AbortSignal | undefined,
  isValid: (value: unknown) => value is T,
): Promise<T[]> {
  const apiBaseUrl = (process.env.NEXT_PUBLIC_API_BASE_URL ?? "").replace(/\/$/, "");
  const response = await fetch(`${apiBaseUrl}/api/v1/sessions`, {
    method: "GET",
    headers: { Accept: "application/json", Authorization: `Bearer ${accessToken}` },
    credentials: "include",
    signal,
  });

  if (!response.ok) {
    throw new SessionListRequestError(
      `Session list request failed with status ${response.status}.`,
      response.status,
    );
  }

  let envelope: unknown;
  try {
    envelope = await response.json();
  } catch {
    throw new SessionListRequestError(
      "Session list response was not valid JSON.",
      response.status,
    );
  }

  const data =
    typeof envelope === "object" &&
    envelope !== null &&
    (envelope as { isSuccess?: unknown }).isSuccess === true
      ? (envelope as { data?: unknown }).data
      : undefined;

  if (!Array.isArray(data) || !data.every(isValid)) {
    throw new SessionListRequestError(
      "Session list response had an invalid envelope.",
      response.status,
    );
  }

  // 숫자로 온 ID 도 여기서 문자열로 통일해, 화면·라우팅이 한 가지 형태만 다루게 한다.
  return data.map((summary) => ({ ...summary, sessionId: String(summary.sessionId) }));
}

/**
 * 내가 참여한 수업 목록을 가져온다(GET /api/v1/sessions).
 *
 * <p>강사로 연 수업과 학생으로 들은 수업이 함께 오고 `role` 로 갈린다. 페이지네이션이 없어 전체가 한 번에 온다 — 검색·정렬·달력 묶음은 받아 온 목록 위에서 화면이 처리한다.
 *
 * <p>모양이 어긋난 항목이 하나라도 있으면 전체를 거절한다. 일부만 받아 두면 화면이 빈 칸을 그리고, 그 원인이 서버 변경인지 이쪽 버그인지 나중에 가릴 수 없다.
 */
export const requestSessionList: SessionListRequester = (accessToken, signal) =>
  fetchSessions(accessToken, signal, isSessionSummary);

/**
 * 같은 목록에서 식별에 필요한 부분만 가져온다.
 *
 * <p>"진행 중인 수업으로 돌아가기" 배너처럼 어느 수업인지만 알면 되는 쪽이 쓴다. 목록 화면이 쓰는 필드가 늘거나 바뀌어도 이쪽은 흔들리지 않는다 — 강의실로 돌아갈 길이 리포트 필드 하나 때문에 막히면
 * 곤란하다.
 */
export const requestSessionIdentities: SessionIdentityRequester = (accessToken, signal) =>
  fetchSessions(accessToken, signal, isSessionIdentity);
