/**
 * 강사 사후 메모 어댑터.
 *
 * - `PUT  /api/v1/sessions/{sessionId}/notes/draft` — 초안 저장. 저장이 성공할 때마다
 *   서버의 30분 비활성 타이머가 초기화된다(FRD §16 NOTE-002). 빈 본문도 유효한 저장이다.
 * - `POST /api/v1/sessions/{sessionId}/notes/finalize` — 작성 완료. 멱등이라 이미 확정된
 *   메모여도 200 이다. 초안 없이 호출하면 메모 없이 완료하는 경로가 된다.
 *
 * 저장된 본문을 되돌려주는 조회 API 는 없다. 응답에도 본문이 없으므로 작성 중인 내용은
 * 화면 상태로만 유지된다.
 */

export type InstructorNoteStatus = "DRAFT" | "FINALIZED";

export type InstructorNoteResult = {
  /** TSID 라 JS 안전 정수 범위를 넘는다. 문자열로만 다뤄야 값이 깨지지 않는다. */
  noteId: string;
  /** FINALIZED 이후에는 수정할 수 없다. */
  status: InstructorNoteStatus;
  /** 초안이면 마지막 입력 시각, 확정이면 확정 시각(UTC ISO). */
  updatedAt: string;
};

/** 본문이 5000자(트림 후)를 넘음 — 400. */
export const NOTE_CONTENT_TOO_LONG = "POSTCLASS_NOTE_001";
/** 이미 확정된 메모 — 409. 30분 비활성 자동 확정 이후의 저장 시도가 여기로 온다. */
export const NOTE_ALREADY_FINALIZED = "POSTCLASS_NOTE_002";
/** 아직 진행 중인 수업 — 409. */
export const SESSION_STILL_LIVE = "SESSION_APP_009";

export class InstructorNoteRequestError extends Error {
  /** HTTP 상태. 응답을 받지 못했으면 0. */
  readonly status: number;
  /** 서버 에러 코드(ApiResponse.code). 같은 409 라도 원인이 갈리므로 코드로 구분한다. */
  readonly code: string | null;

  constructor(message: string, status: number, code: string | null = null) {
    super(message);
    this.name = "InstructorNoteRequestError";
    this.status = status;
    this.code = code;
  }
}

export type NoteDraftSaver = (
  sessionId: string,
  content: string,
  accessToken: string,
  signal?: AbortSignal,
) => Promise<InstructorNoteResult>;

export type NoteFinalizer = (
  sessionId: string,
  accessToken: string,
  signal?: AbortSignal,
) => Promise<InstructorNoteResult>;

const idOf = (value: unknown): string | null => {
  if (typeof value === "string" && value.length > 0) return value;
  if (typeof value === "number" && Number.isFinite(value)) return String(value);
  return null;
};

type NoteResultPayload = {
  noteId: string | number;
  status: InstructorNoteStatus;
  updatedAt: string;
};

const isNoteResult = (value: unknown): value is NoteResultPayload => {
  if (typeof value !== "object" || value === null) {
    return false;
  }
  const result = value as Record<string, unknown>;
  return (
    idOf(result.noteId) !== null &&
    (result.status === "DRAFT" || result.status === "FINALIZED") &&
    typeof result.updatedAt === "string"
  );
};

const requestNote = async (
  label: string,
  url: string,
  init: RequestInit,
): Promise<InstructorNoteResult> => {
  let response: Response;
  try {
    response = await fetch(url, init);
  } catch (error) {
    // 응답이 없으면 서버가 받았는지 알 수 없다. 초안 저장은 같은 내용으로 재시도해도 안전하다.
    throw new InstructorNoteRequestError(`${label} request failed: ${String(error)}`, 0);
  }

  if (!response.ok) {
    let code: string | null = null;
    try {
      const failure: unknown = await response.json();
      if (
        typeof failure === "object" &&
        failure !== null &&
        typeof (failure as { code?: unknown }).code === "string"
      ) {
        code = (failure as { code: string }).code;
      }
    } catch {
      // 실패 본문이 JSON 이 아니면 상태 코드만으로 처리한다.
    }
    throw new InstructorNoteRequestError(
      `${label} failed with status ${response.status}.`,
      response.status,
      code,
    );
  }

  let envelope: unknown;
  try {
    envelope = await response.json();
  } catch {
    throw new InstructorNoteRequestError(`${label} response was not valid JSON.`, response.status);
  }

  if (
    typeof envelope !== "object" ||
    envelope === null ||
    (envelope as { isSuccess?: unknown }).isSuccess !== true ||
    !isNoteResult((envelope as { data?: unknown }).data)
  ) {
    throw new InstructorNoteRequestError(
      `${label} response had an invalid envelope.`,
      response.status,
    );
  }

  const data = (envelope as { data: NoteResultPayload }).data;
  return { noteId: String(data.noteId), status: data.status, updatedAt: data.updatedAt };
};

const apiBaseUrl = () => (process.env.NEXT_PUBLIC_API_BASE_URL ?? "").replace(/\/$/, "");

/** 요청 본문. 계약에 있는 값만 담는다(서버가 계약 밖 필드에 400 을 낸다). */
interface SaveNoteDraftRequestBody {
  readonly content: string;
}

export const saveNoteDraft: NoteDraftSaver = async (sessionId, content, accessToken, signal) => {
  const body: SaveNoteDraftRequestBody = { content };
  return requestNote(
    "Save note draft",
    `${apiBaseUrl()}/api/v1/sessions/${encodeURIComponent(sessionId)}/notes/draft`,
    {
      method: "PUT",
      headers: {
        "Content-Type": "application/json",
        Accept: "application/json",
        Authorization: `Bearer ${accessToken}`,
      },
      credentials: "include",
      body: JSON.stringify(body),
      signal,
    },
  );
};

export const finalizeNote: NoteFinalizer = async (sessionId, accessToken, signal) =>
  requestNote(
    "Finalize note",
    `${apiBaseUrl()}/api/v1/sessions/${encodeURIComponent(sessionId)}/notes/finalize`,
    {
      method: "POST",
      headers: { Accept: "application/json", Authorization: `Bearer ${accessToken}` },
      credentials: "include",
      signal,
    },
  );
