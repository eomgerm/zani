import { beforeEach, describe, expect, it, vi } from "vitest";

import { prejoinStorageKey, readPrejoinResult, writePrejoinResult } from "./prejoinResult";

const RESULT = {
  cameraDeviceId: "cam-1",
  microphoneDeviceId: "mic-1",
  testedAt: "2026-07-26T12:00:00.000Z",
};

beforeEach(() => {
  sessionStorage.clear();
  vi.restoreAllMocks();
});

describe("prejoinResult", () => {
  it("reads back what the pre-join check stored", () => {
    writePrejoinResult("ABC123", RESULT);

    expect(readPrejoinResult("ABC123")).toEqual(RESULT);
  });

  it("keeps one result per invite code", () => {
    writePrejoinResult("ABC123", RESULT);

    expect(readPrejoinResult("OTHER1")).toBeNull();
    expect(sessionStorage.getItem(prejoinStorageKey("ABC123"))).not.toBeNull();
  });

  it("returns null when nothing was stored", () => {
    expect(readPrejoinResult("ABC123")).toBeNull();
  });

  it("returns null for a broken payload instead of throwing", () => {
    sessionStorage.setItem(prejoinStorageKey("ABC123"), "{not-json");

    expect(readPrejoinResult("ABC123")).toBeNull();
  });

  it("returns null when the tested time is missing", () => {
    sessionStorage.setItem(
      prejoinStorageKey("ABC123"),
      JSON.stringify({ cameraDeviceId: "cam-1", microphoneDeviceId: "mic-1" }),
    );

    expect(readPrejoinResult("ABC123")).toBeNull();
  });

  it("falls back to the default devices when storage is blocked", () => {
    vi.spyOn(sessionStorage, "getItem").mockImplementation(() => {
      throw new DOMException("blocked", "SecurityError");
    });

    expect(readPrejoinResult("ABC123")).toBeNull();
  });

  it("does not fail the pre-join flow when storage rejects the write", () => {
    vi.spyOn(sessionStorage, "setItem").mockImplementation(() => {
      throw new DOMException("quota", "QuotaExceededError");
    });

    expect(() => writePrejoinResult("ABC123", RESULT)).not.toThrow();
  });
});
