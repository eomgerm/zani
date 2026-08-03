export type UpdateReportEmailResult = {
  /** 변경 후 리포트 완료 이메일 수신 여부. */
  reportEmailEnabled: boolean;
};

export class UpdateReportEmailRequestError extends Error {
  /** HTTP 상태. 응답을 받지 못했으면 0. */
  readonly status: number;

  constructor(message: string, status: number) {
    super(message);
    this.name = "UpdateReportEmailRequestError";
    this.status = status;
  }
}

export type ReportEmailUpdater = (
  accessToken: string,
  reportEmailEnabled: boolean,
  signal?: AbortSignal,
) => Promise<UpdateReportEmailResult>;

const isUpdateReportEmailResult = (value: unknown): value is UpdateReportEmailResult => {
  if (typeof value !== "object" || value === null) {
    return false;
  }
  return typeof (value as Record<string, unknown>).reportEmailEnabled === "boolean";
};

/**
 * 강의 리포트 완료 이메일 수신 설정을 켜거나 끈다(PATCH /api/v1/members/me).
 * 서버는 Access Token 의 사용자 본인 설정만 바꾸므로 대상 회원을 따로 넘기지 않는다.
 * 토큰은 인증 컨텍스트(useAuth)가 메모리에만 들고 있는 값을 그대로 전달받는다(저장·로그 금지).
 */
export const updateReportEmail: ReportEmailUpdater = async (
  accessToken,
  reportEmailEnabled,
  signal,
) => {
  const apiBaseUrl = (process.env.NEXT_PUBLIC_API_BASE_URL ?? "").replace(/\/$/, "");
  const response = await fetch(`${apiBaseUrl}/api/v1/members/me`, {
    method: "PATCH",
    headers: {
      "Content-Type": "application/json",
      Accept: "application/json",
      Authorization: `Bearer ${accessToken}`,
    },
    credentials: "include",
    body: JSON.stringify({ reportEmailEnabled }),
    signal,
  });

  if (!response.ok) {
    throw new UpdateReportEmailRequestError(
      `Update report email setting failed with status ${response.status}.`,
      response.status,
    );
  }

  let envelope: unknown;
  try {
    envelope = await response.json();
  } catch {
    throw new UpdateReportEmailRequestError(
      "Update report email setting response was not valid JSON.",
      response.status,
    );
  }

  if (
    typeof envelope !== "object" ||
    envelope === null ||
    (envelope as { isSuccess?: unknown }).isSuccess !== true ||
    !isUpdateReportEmailResult((envelope as { data?: unknown }).data)
  ) {
    throw new UpdateReportEmailRequestError(
      "Update report email setting response had an invalid envelope.",
      response.status,
    );
  }

  return (envelope as { data: UpdateReportEmailResult }).data;
};
