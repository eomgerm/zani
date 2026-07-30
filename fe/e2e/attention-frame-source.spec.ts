import { expect, test } from "@playwright/test";

import type { AttentionFrameProbeResult } from "./fixtures/attention-frame-source";

test("실제 Worker가 native 카메라 clone의 첫 VideoFrame을 읽고 원본 Track은 유지한다", async ({
  page,
}) => {
  await page.goto("http://localhost:3101/e2e/fixtures/attention-frame-source.html");

  const result = await page.evaluate(() =>
    (
      window as typeof window & {
        runAttentionFrameProbe(): Promise<AttentionFrameProbeResult>;
      }
    ).runAttentionFrameProbe(),
  );

  expect(result.firstFrameTimestampMs).toBeGreaterThanOrEqual(0);
  expect(result.receivedNativeVideoFrame).toBe(true);
  expect(result.analysisReadyState).toBe("ended");
  expect(result.originalReadyState).toBe("live");
  expect(result.originalMuted).toBe(false);
});
