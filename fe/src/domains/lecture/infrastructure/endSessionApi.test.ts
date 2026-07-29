import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { endSession, EndSessionRequestError } from "./endSessionApi";

const jsonResponse = (body: unknown, status = 200) =>
  ({
    ok: status >= 200 && status < 300,
    status,
    json: () => Promise.resolve(body),
  }) as Response;

const fetchMock = vi.fn();

beforeEach(() => {
  vi.stubGlobal("fetch", fetchMock);
  fetchMock.mockReset();
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("endSession", () => {
  it("posts to the session end endpoint with credentials", async () => {
    fetchMock.mockResolvedValue(
      jsonResponse({ isSuccess: true, data: { sessionId: "123", status: "ENDED", ended: true } }),
    );

    const result = await endSession("123", "test-access-token");

    expect(result).toEqual({ sessionId: "123", status: "ENDED", ended: true });
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toContain("/api/v1/sessions/123/end");
    expect(init.method).toBe("POST");
    expect(init.credentials).toBe("include");
  });

  it("sends the access token so the server can identify the instructor", async () => {
    fetchMock.mockResolvedValue(
      jsonResponse({ isSuccess: true, data: { sessionId: "123", status: "ENDED", ended: true } }),
    );

    await endSession("123", "test-access-token");

    expect(fetchMock.mock.calls[0][1].headers.Authorization).toBe("Bearer test-access-token");
  });

  it("reports an already ended session as a non-transition", async () => {
    fetchMock.mockResolvedValue(
      jsonResponse({ isSuccess: true, data: { sessionId: "123", status: "ENDED", ended: false } }),
    );

    await expect(endSession("123", "t")).resolves.toMatchObject({ ended: false });
  });

  it("keeps the HTTP status so the caller can explain a rejection", async () => {
    fetchMock.mockResolvedValue(jsonResponse({}, 403));

    await expect(endSession("123", "t")).rejects.toMatchObject({
      name: "EndSessionRequestError",
      status: 403,
    });
  });

  it("rejects an envelope that is not a success", async () => {
    fetchMock.mockResolvedValue(jsonResponse({ isSuccess: false, data: null }));

    await expect(endSession("123", "t")).rejects.toBeInstanceOf(EndSessionRequestError);
  });

  it("rejects a payload missing the ended flag", async () => {
    fetchMock.mockResolvedValue(
      jsonResponse({ isSuccess: true, data: { sessionId: "123", status: "ENDED" } }),
    );

    await expect(endSession("123", "t")).rejects.toBeInstanceOf(EndSessionRequestError);
  });
});
