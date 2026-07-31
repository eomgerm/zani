import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
  finalizeNote,
  InstructorNoteRequestError,
  NOTE_ALREADY_FINALIZED,
  NOTE_CONTENT_TOO_LONG,
  saveNoteDraft,
  SESSION_STILL_LIVE,
} from "./instructorNoteApi";

const jsonResponse = (body: unknown, status = 200) =>
  ({
    ok: status >= 200 && status < 300,
    status,
    json: () => Promise.resolve(body),
  }) as Response;

const draftEnvelope = {
  isSuccess: true,
  data: { noteId: "742891573920571392", status: "DRAFT", updatedAt: "2026-07-30T09:12:00Z" },
};

const fetchMock = vi.fn();

beforeEach(() => {
  vi.stubGlobal("fetch", fetchMock);
  fetchMock.mockReset();
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("saveNoteDraft", () => {
  it("puts the draft content with credentials and the bearer token", async () => {
    fetchMock.mockResolvedValue(jsonResponse(draftEnvelope));

    const result = await saveNoteDraft("123", "재귀 종료 조건 정리", "test-access-token");

    expect(result).toEqual({
      noteId: "742891573920571392",
      status: "DRAFT",
      updatedAt: "2026-07-30T09:12:00Z",
    });
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toContain("/api/v1/sessions/123/notes/draft");
    expect(init.method).toBe("PUT");
    expect(init.credentials).toBe("include");
    expect(init.headers.Authorization).toBe("Bearer test-access-token");
    expect(init.headers["Content-Type"]).toBe("application/json");
    expect(JSON.parse(init.body)).toEqual({ content: "재귀 종료 조건 정리" });
  });

  it("normalizes a numeric noteId to a string", async () => {
    fetchMock.mockResolvedValue(
      jsonResponse({
        isSuccess: true,
        data: { noteId: 42, status: "DRAFT", updatedAt: "2026-07-30T09:12:00Z" },
      }),
    );

    const result = await saveNoteDraft("123", "", "t");

    expect(result.noteId).toBe("42");
  });

  it("keeps the HTTP status and server code so the caller can branch", async () => {
    fetchMock.mockResolvedValue(
      jsonResponse(
        { isSuccess: false, code: NOTE_ALREADY_FINALIZED, message: "already finalized" },
        409,
      ),
    );

    await expect(saveNoteDraft("123", "x", "t")).rejects.toMatchObject({
      name: "InstructorNoteRequestError",
      status: 409,
      code: NOTE_ALREADY_FINALIZED,
    });
  });

  it("keeps the 400 code for an over-length draft", async () => {
    fetchMock.mockResolvedValue(
      jsonResponse({ isSuccess: false, code: NOTE_CONTENT_TOO_LONG, message: "too long" }, 400),
    );

    await expect(saveNoteDraft("123", "가".repeat(5001), "t")).rejects.toMatchObject({
      status: 400,
      code: NOTE_CONTENT_TOO_LONG,
    });
  });

  it("still reports the status when the failure body is not JSON", async () => {
    fetchMock.mockResolvedValue({
      ok: false,
      status: 500,
      json: () => Promise.reject(new Error("no body")),
    } as unknown as Response);

    await expect(saveNoteDraft("123", "x", "t")).rejects.toMatchObject({
      status: 500,
      code: null,
    });
  });

  it("reports status 0 when the request never reaches the server", async () => {
    fetchMock.mockRejectedValue(new TypeError("network down"));

    await expect(saveNoteDraft("123", "x", "t")).rejects.toMatchObject({
      name: "InstructorNoteRequestError",
      status: 0,
    });
  });

  it("rejects an envelope that is not a success", async () => {
    fetchMock.mockResolvedValue(jsonResponse({ isSuccess: false, data: null }));

    await expect(saveNoteDraft("123", "x", "t")).rejects.toBeInstanceOf(
      InstructorNoteRequestError,
    );
  });

  it("rejects a payload with an unknown note status", async () => {
    fetchMock.mockResolvedValue(
      jsonResponse({
        isSuccess: true,
        data: { noteId: "1", status: "OPEN", updatedAt: "2026-07-30T09:12:00Z" },
      }),
    );

    await expect(saveNoteDraft("123", "x", "t")).rejects.toBeInstanceOf(
      InstructorNoteRequestError,
    );
  });
});

describe("finalizeNote", () => {
  it("posts to the finalize endpoint without a body", async () => {
    fetchMock.mockResolvedValue(
      jsonResponse({
        isSuccess: true,
        data: { noteId: "1", status: "FINALIZED", updatedAt: "2026-07-30T09:20:00Z" },
      }),
    );

    const result = await finalizeNote("123", "test-access-token");

    expect(result.status).toBe("FINALIZED");
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toContain("/api/v1/sessions/123/notes/finalize");
    expect(init.method).toBe("POST");
    expect(init.body).toBeUndefined();
    expect(init.credentials).toBe("include");
    expect(init.headers.Authorization).toBe("Bearer test-access-token");
  });

  it("keeps the code for a still-live session", async () => {
    fetchMock.mockResolvedValue(
      jsonResponse({ isSuccess: false, code: SESSION_STILL_LIVE, message: "still live" }, 409),
    );

    await expect(finalizeNote("123", "t")).rejects.toMatchObject({
      status: 409,
      code: SESSION_STILL_LIVE,
    });
  });
});
