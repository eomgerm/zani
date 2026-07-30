import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
  cameraGuideSuppressionKey,
  sessionStorageCameraGuideSuppression as store,
} from "./cameraGuideSuppression";

beforeEach(() => {
  sessionStorage.clear();
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe("sessionStorageCameraGuideSuppression", () => {
  it("reports nothing suppressed before the student answers", () => {
    expect(store.isSuppressed("s1")).toBe(false);
  });

  it("remembers the suppression for the class it was made in", () => {
    store.suppress("s1");

    expect(store.isSuppressed("s1")).toBe(true);
  });

  // 키를 수업별로 나누지 않으면 다음 수업까지 억제가 따라간다.
  it("keeps classes apart", () => {
    store.suppress("session-a");

    expect(store.isSuppressed("session-b")).toBe(false);
    expect(sessionStorage.getItem(cameraGuideSuppressionKey("session-a"))).toBe("1");
  });

  // 사생활 보호 모드 등에서 저장소 접근이 막혀도 수업 화면이 깨져서는 안 된다.
  it("falls back to not suppressing when the store cannot be read", () => {
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new Error("access denied");
    });

    expect(store.isSuppressed("s1")).toBe(false);
  });

  it("swallows a failed write instead of breaking the class", () => {
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("quota exceeded");
    });

    expect(() => store.suppress("s1")).not.toThrow();
  });
});
