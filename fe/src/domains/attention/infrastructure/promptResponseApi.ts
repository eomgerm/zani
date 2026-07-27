export type PromptResponseValue = "UNDERSTOOD" | "CONFUSED" | "MISSED";

export type PromptResponseSender = (
  sessionId: string,
  promptId: string,
  value: PromptResponseValue,
  signal?: AbortSignal,
) => Promise<void>;

export class PromptResponseSendError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "PromptResponseSendError";
  }
}

export const sendPromptResponse: PromptResponseSender = async (
  sessionId,
  promptId,
  value,
  signal,
) => {
  const apiBaseUrl = (process.env.NEXT_PUBLIC_API_BASE_URL ?? "").replace(/\/$/, "");
  const response = await fetch(
    `${apiBaseUrl}/api/v1/sessions/${encodeURIComponent(sessionId)}/prompts/${encodeURIComponent(promptId)}/responses`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "application/json" },
      credentials: "include",
      body: JSON.stringify({ value }),
      signal,
    },
  );

  if (!response.ok) {
    throw new PromptResponseSendError(
      `Prompt response request failed with status ${response.status}.`,
    );
  }
};
