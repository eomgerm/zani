export type GoogleLoginResult = {
  accessToken: string;
  accessTokenExpiresAt: string;
  email: string;
  displayName: string;
  profileImageUrl: string | null;
  newMember: boolean;
};

export type GoogleLoginRequester = (
  idToken: string,
  signal?: AbortSignal,
) => Promise<GoogleLoginResult>;

export class GoogleLoginRequestError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "GoogleLoginRequestError";
  }
}

const isGoogleLoginResult = (value: unknown): value is GoogleLoginResult => {
  if (typeof value !== "object" || value === null) {
    return false;
  }

  const result = value as Record<string, unknown>;
  const isNonBlankString = (field: unknown) =>
    typeof field === "string" && field.trim().length > 0;
  return (
    isNonBlankString(result.accessToken) &&
    isNonBlankString(result.accessTokenExpiresAt) &&
    isNonBlankString(result.email) &&
    isNonBlankString(result.displayName) &&
    (result.profileImageUrl === null || isNonBlankString(result.profileImageUrl)) &&
    typeof result.newMember === "boolean"
  );
};

export const loginWithGoogle: GoogleLoginRequester = async (idToken, signal) => {
  const apiBaseUrl = (process.env.NEXT_PUBLIC_API_BASE_URL ?? "").replace(/\/$/, "");
  const response = await fetch(`${apiBaseUrl}/api/v1/auth/login/google`, {
    method: "POST",
    headers: { "Content-Type": "application/json", Accept: "application/json" },
    credentials: "include",
    body: JSON.stringify({ idToken }),
    signal,
  });

  if (!response.ok) {
    throw new GoogleLoginRequestError(
      `Google login request failed with status ${response.status}.`,
    );
  }

  let envelope: unknown;
  try {
    envelope = await response.json();
  } catch {
    throw new GoogleLoginRequestError("Google login response was not valid JSON.");
  }

  if (
    typeof envelope !== "object" ||
    envelope === null ||
    (envelope as { isSuccess?: unknown }).isSuccess !== true ||
    !isGoogleLoginResult((envelope as { data?: unknown }).data)
  ) {
    throw new GoogleLoginRequestError("Google login response had an invalid envelope.");
  }

  return (envelope as { data: GoogleLoginResult }).data;
};
