import { beforeEach, describe, expect, it, vi } from "vitest";

import type { AttentionModel } from "./attentionModel";
import { createAttentionModelLoader, MODEL_LOAD_RETRY } from "./attentionModelLoader";

interface ScheduledRetry {
  readonly callback: () => void;
  readonly delayMs: number;
}

function fakeModel(): AttentionModel {
  return {
    metadata: {},
    predict: async () => ({ label: "Engaged", probabilities: [0, 0, 1, 0] }),
    dispose: async () => {},
  } as unknown as AttentionModel;
}

describe("createAttentionModelLoader", () => {
  let scheduled: ScheduledRetry[];
  let createModel: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    scheduled = [];
    createModel = vi.fn(async () => fakeModel());
  });

  function loader() {
    return createAttentionModelLoader({
      createModel: createModel as unknown as () => Promise<AttentionModel>,
      schedule: (callback: () => void, delayMs: number) => {
        scheduled.push({ callback, delayMs });
      },
    });
  }

  /** 예약된 재시도를 실제 타이머 대신 즉시 실행한다. */
  function elapse(): void {
    const next = scheduled.shift();
    if (!next) throw new Error("예약된 재시도가 없습니다.");
    next.callback();
  }

  function failOnce(message = "네트워크가 끊겼습니다."): void {
    createModel.mockRejectedValueOnce(new Error(message));
  }

  it("loads the model once and reuses it for later windows", async () => {
    const model = loader();

    const first = await model.load();
    const second = await model.load();

    expect(first).toEqual({ kind: "ready", model: expect.anything() });
    expect(second.kind).toBe("ready");
    expect(createModel).toHaveBeenCalledTimes(1);
  });

  it("reports a retry instead of a permanent failure on the first load error", async () => {
    failOnce("모델 다운로드가 끊겼습니다.");
    const model = loader();

    const outcome = await model.load();

    expect(outcome).toEqual({ kind: "retrying", message: "모델 다운로드가 끊겼습니다." });
  });

  it("waits 30 seconds before trying again", async () => {
    failOnce();
    const model = loader();

    await model.load();

    expect(scheduled).toHaveLength(1);
    expect(scheduled[0]?.delayMs).toBe(30_000);
  });

  it("does not reload while a retry is still waiting", async () => {
    failOnce();
    const model = loader();
    await model.load();

    const outcome = await model.load();

    expect(outcome.kind).toBe("retrying");
    expect(createModel).toHaveBeenCalledTimes(1);
  });

  it("recovers without a rejoin once a retry succeeds", async () => {
    failOnce();
    const model = loader();
    await model.load();

    elapse();
    const outcome = await model.load();

    expect(outcome.kind).toBe("ready");
    expect(createModel).toHaveBeenCalledTimes(2);
  });

  it("gives up after three retries", async () => {
    createModel.mockRejectedValue(new Error("모델을 받지 못했습니다."));
    const model = loader();

    let outcome = await model.load();
    expect(outcome.kind).toBe("retrying");
    for (let retry = 0; retry < MODEL_LOAD_RETRY.maxRetries - 1; retry += 1) {
      elapse();
      outcome = await model.load();
      expect(outcome.kind).toBe("retrying");
    }
    elapse();
    outcome = await model.load();

    expect(outcome).toEqual({ kind: "exhausted", message: "모델을 받지 못했습니다." });
    // 최초 1회 + 재시도 3회까지만 시도한다.
    expect(createModel).toHaveBeenCalledTimes(MODEL_LOAD_RETRY.maxRetries + 1);
  });

  it("stops scheduling attempts once the retries are exhausted", async () => {
    createModel.mockRejectedValue(new Error("모델을 받지 못했습니다."));
    const model = loader();
    await model.load();
    for (let retry = 0; retry < MODEL_LOAD_RETRY.maxRetries; retry += 1) {
      elapse();
      await model.load();
    }

    expect(scheduled).toHaveLength(0);
  });

  it("keeps reporting the exhausted verdict without loading again", async () => {
    createModel.mockRejectedValue(new Error("브라우저가 지원하지 않습니다."));
    const model = loader();
    await model.load();
    for (let retry = 0; retry < MODEL_LOAD_RETRY.maxRetries; retry += 1) {
      elapse();
      await model.load();
    }
    const attempts = createModel.mock.calls.length;

    const outcome = await model.load();

    expect(outcome.kind).toBe("exhausted");
    expect(createModel).toHaveBeenCalledTimes(attempts);
  });

  it("counts one failure when several windows share the same attempt", async () => {
    failOnce();
    const model = loader();

    const [first, second] = await Promise.all([model.load(), model.load()]);

    expect(first.kind).toBe("retrying");
    expect(second.kind).toBe("retrying");
    // 창 두 개가 같은 시도를 기다렸으므로 재시도 예약도 한 번뿐이다.
    expect(scheduled).toHaveLength(1);
    expect(createModel).toHaveBeenCalledTimes(1);
  });
});
