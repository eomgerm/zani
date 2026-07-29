import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const ACCESS_TOKEN = "test-access-token";

import { PresenceReportError, reportPresence } from "./presenceApi";

const HEARTBEAT = { heartbeatAt: "2026-07-26T12:00:00.000Z", connectionState: "CONNECTED" } as const;

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

describe("reportPresence", () => {
  it("posts the heartbeat to the session presence endpoint with credentials", async () => {
    fetchMock.mockResolvedValue(
      jsonResponse({ isSuccess: true, data: { reconnectStatus: "CONNECTED", sessionEnded: false } }),
    );

    const snapshot = await reportPresence("session-1", HEARTBEAT, ACCESS_TOKEN);

    expect(snapshot).toEqual({ reconnectStatus: "CONNECTED", sessionEnded: false });
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toContain("/api/v1/sessions/session-1/presence");
    expect(init.method).toBe("POST");
    expect(init.credentials).toBe("include");
    expect(JSON.parse(init.body)).toEqual(HEARTBEAT);
  });

  it("escapes the session ID in the request path", async () => {
    fetchMock.mockResolvedValue(
      jsonResponse({ isSuccess: true, data: { reconnectStatus: "CONNECTED", sessionEnded: false } }),
    );

    await reportPresence("a/b?c", HEARTBEAT, ACCESS_TOKEN);

    expect(fetchMock.mock.calls[0][0]).toContain("/sessions/a%2Fb%3Fc/presence");
  });

  it("reports the session end signal from the server", async () => {
    fetchMock.mockResolvedValue(
      jsonResponse({
        isSuccess: true,
        data: { reconnectStatus: "SESSION_ENDED", sessionEnded: true },
      }),
    );

    await expect(reportPresence("session-1", HEARTBEAT, ACCESS_TOKEN)).resolves.toEqual({
      reconnectStatus: "SESSION_ENDED",
      sessionEnded: true,
    });
  });

  it("keeps the HTTP status so the caller can stop on a rejected report", async () => {
    fetchMock.mockResolvedValue(jsonResponse({}, 403));

    await expect(reportPresence("session-1", HEARTBEAT, ACCESS_TOKEN)).rejects.toMatchObject({
      name: "PresenceReportError",
      status: 403,
    });
  });

  it("rejects a response whose envelope is not a success", async () => {
    fetchMock.mockResolvedValue(jsonResponse({ isSuccess: false, data: null }));

    await expect(reportPresence("session-1", HEARTBEAT, ACCESS_TOKEN)).rejects.toBeInstanceOf(
      PresenceReportError,
    );
  });

  it("rejects a response with an unknown reconnect status", async () => {
    fetchMock.mockResolvedValue(
      jsonResponse({ isSuccess: true, data: { reconnectStatus: "WAT", sessionEnded: false } }),
    );

    await expect(reportPresence("session-1", HEARTBEAT, ACCESS_TOKEN)).rejects.toBeInstanceOf(
      PresenceReportError,
    );
  });

  it("rejects a response body that is not JSON", async () => {
    fetchMock.mockResolvedValue({
      ok: true,
      status: 200,
      json: () => Promise.reject(new Error("not json")),
    } as unknown as Response);

    await expect(reportPresence("session-1", HEARTBEAT, ACCESS_TOKEN)).rejects.toBeInstanceOf(
      PresenceReportError,
    );
  });
});
