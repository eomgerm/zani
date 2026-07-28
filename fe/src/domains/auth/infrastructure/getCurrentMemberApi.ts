export type CurrentMember = {
  email: string;
  displayName: string;
  profileImageUrl: string | null;
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

  return (envelope as { data: CurrentMember }).data;
};
