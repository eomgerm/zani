export type CurrentMember = {
  email: string;
  displayName: string;
  profileImageUrl: string | null;
  /** 강의 리포트 완료 이메일 수신 여부. 예전 응답 호환을 위해 값이 없으면 수신(true)으로 본다. */
  reportEmailEnabled: boolean;
};

export type CurrentMemberRequester = (
  accessToken: string,
  signal?: AbortSignal,
) => Promise<CurrentMember>;

export class GetCurrentMemberRequestError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "GetCurrentMemberRequestError";
  }
}

const isCurrentMember = (value: unknown): value is CurrentMember => {
  if (typeof value !== "object" || value === null) {
    return false;
  }

  const member = value as Record<string, unknown>;
  const isNonBlankString = (field: unknown) =>
    typeof field === "string" && field.trim().length > 0;
  return (
    isNonBlankString(member.email) &&
    isNonBlankString(member.displayName) &&
    (member.profileImageUrl === null || isNonBlankString(member.profileImageUrl))
  );
};

export const getCurrentMember: CurrentMemberRequester = async (accessToken, signal) => {
  const apiBaseUrl = (process.env.NEXT_PUBLIC_API_BASE_URL ?? "").replace(/\/$/, "");
  const response = await fetch(`${apiBaseUrl}/api/v1/members/me`, {
    method: "GET",
    headers: { Accept: "application/json", Authorization: `Bearer ${accessToken}` },
    credentials: "include",
    signal,
  });

  if (!response.ok) {
    throw new GetCurrentMemberRequestError(
      `Get current member request failed with status ${response.status}.`,
    );
  }

  let envelope: unknown;
  try {
    envelope = await response.json();
  } catch {
    throw new GetCurrentMemberRequestError("Get current member response was not valid JSON.");
  }

  if (
    typeof envelope !== "object" ||
    envelope === null ||
    (envelope as { isSuccess?: unknown }).isSuccess !== true ||
    !isCurrentMember((envelope as { data?: unknown }).data)
  ) {
    throw new GetCurrentMemberRequestError("Get current member response had an invalid envelope.");
  }

  const data = (envelope as { data: Record<string, unknown> }).data;
  return {
    email: data.email as string,
    displayName: data.displayName as string,
    profileImageUrl: data.profileImageUrl as string | null,
    reportEmailEnabled: typeof data.reportEmailEnabled === "boolean" ? data.reportEmailEnabled : true,
  };
};
