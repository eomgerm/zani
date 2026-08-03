import { beforeEach, describe, expect, it, vi } from "vitest";

import type { AttentionPrediction } from "../domain/attentionPrediction";
import { createAttentionInferenceClient } from "./attentionInferenceClient";
import type {
  AttentionWorkerPort,
  AttentionWorkerRequest,
  AttentionWorkerResponse,
} from "./attentionWorkerProtocol";

interface SentMessage {
  readonly message: AttentionWorkerRequest;
  readonly transfer: Transferable[] | undefined;
}

class FakeWorker implements AttentionWorkerPort {
  readonly sent: SentMessage[] = [];
  terminated = false;
  onmessage: ((event: MessageEvent<AttentionWorkerResponse>) => void) | null = null;
  onerror: ((event: unknown) => void) | null = null;

  postMessage(message: AttentionWorkerRequest, transfer?: Transferable[]): void {
    this.sent.push({ message, transfer });
  }

  terminate(): void {
    this.terminated = true;
  }

  respond(response: AttentionWorkerResponse): void {
    this.onmessage?.({ data: response } as MessageEvent<AttentionWorkerResponse>);
  }

  /** 마지막으로 받은 predict 요청에 판정 결과로 응답한다. */
  respondToLatest(label: AttentionPrediction["label"] = "Engaged"): void {
    const last = this.sent.at(-1)?.message;
    if (!last) throw new Error("predict 요청이 없습니다.");
    this.respond({
      type: "prediction",
      requestId: last.requestId,
      prediction: { label, probabilities: [0, 0, 1, 0] },
    });
  }
}

function tokens(fill: number): Float32Array {
  return new Float32Array(20 * 98).fill(fill);
}

describe("createAttentionInferenceClient", () => {
  let worker: FakeWorker;
  let onPrediction: ReturnType<typeof vi.fn>;
  let onFailure: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    worker = new FakeWorker();
    onPrediction = vi.fn();
    onFailure = vi.fn();
  });

  function client() {
    return createAttentionInferenceClient({
      createWorker: () => worker,
      onPrediction,
      onFailure,
    });
  }

  it("hands the first window to the worker right away", () => {
    client().submit(tokens(1));

    expect(worker.sent).toHaveLength(1);
    expect(worker.sent[0]?.message.type).toBe("predict");
  });

  it("transfers the token buffer instead of copying it", () => {
    const window = tokens(1);

    client().submit(window);

    expect(worker.sent[0]?.transfer).toEqual([window.buffer]);
  });

  it("reports the prediction the worker sends back", () => {
    const inference = client();
    inference.submit(tokens(1));

    worker.respondToLatest("Highly-Engaged");

    expect(onPrediction).toHaveBeenCalledWith({
      label: "Highly-Engaged",
      probabilities: [0, 0, 1, 0],
    });
  });

  it("keeps only the newest window while inference is in flight", () => {
    const inference = client();
    inference.submit(tokens(1));
    inference.submit(tokens(2));
    inference.submit(tokens(3));

    // 추론이 끝나기 전에는 첫 요청 하나만 나가 있다.
    expect(worker.sent).toHaveLength(1);

    worker.respondToLatest();

    // 밀린 두 번째는 버리고 가장 최신 창만 이어서 보낸다.
    expect(worker.sent).toHaveLength(2);
    expect(worker.sent[1]?.message.tokens[0]).toBe(3);
  });

  it("goes idle when nothing was dropped while waiting", () => {
    const inference = client();
    inference.submit(tokens(1));
    worker.respondToLatest();

    expect(worker.sent).toHaveLength(1);

    inference.submit(tokens(2));

    expect(worker.sent).toHaveLength(2);
  });

  it("ignores a response whose request was already superseded", () => {
    const inference = client();
    inference.submit(tokens(1));

    worker.respond({
      type: "prediction",
      requestId: 999,
      prediction: { label: "Engaged", probabilities: [0, 0, 1, 0] },
    });

    expect(onPrediction).not.toHaveBeenCalled();
  });

  it("stops accepting windows once the model turns out to be unavailable", () => {
    const inference = client();
    inference.submit(tokens(1));

    worker.respond({
      type: "failure",
      requestId: 1,
      kind: "modelUnavailable",
      message: "학습된 모델이 없습니다.",
    });
    inference.submit(tokens(2));

    expect(onFailure).toHaveBeenCalledWith({
      kind: "modelUnavailable",
      message: "학습된 모델이 없습니다.",
    });
    expect(worker.sent).toHaveLength(1);
  });

  it("keeps sending windows while the model load is still being retried", () => {
    const inference = client();
    inference.submit(tokens(1));

    worker.respond({
      type: "failure",
      requestId: 1,
      kind: "modelLoadRetrying",
      message: "모델 다운로드가 끊겼습니다.",
    });
    inference.submit(tokens(2));

    expect(onFailure).toHaveBeenCalledWith({
      kind: "modelLoadRetrying",
      message: "모델 다운로드가 끊겼습니다.",
    });
    // 재시도가 남아 있는 동안에는 판정을 끄지 않는다.
    expect(worker.sent).toHaveLength(2);
  });

  it("retries the next window after a single failed inference", () => {
    const inference = client();
    inference.submit(tokens(1));

    worker.respond({
      type: "failure",
      requestId: 1,
      kind: "inferenceFailed",
      message: "추론 실패",
    });
    inference.submit(tokens(2));

    expect(onFailure).toHaveBeenCalledWith({ kind: "inferenceFailed", message: "추론 실패" });
    expect(worker.sent).toHaveLength(2);
  });

  it("treats a worker crash as an unavailable model", () => {
    client();

    worker.onerror?.(new ErrorEvent("error", { message: "worker 초기화 실패" }));

    expect(onFailure).toHaveBeenCalledWith(
      expect.objectContaining({ kind: "modelUnavailable" }),
    );
  });

  it("terminates the worker and ignores late responses after teardown", () => {
    const inference = client();
    inference.submit(tokens(1));
    inference.terminate();

    worker.respondToLatest();

    expect(worker.terminated).toBe(true);
    expect(onPrediction).not.toHaveBeenCalled();
  });

  it("starts the worker immediately so the model is warm before the first window", () => {
    const createWorker = vi.fn(() => worker);

    createAttentionInferenceClient({ createWorker, onPrediction, onFailure });

    expect(createWorker).toHaveBeenCalledTimes(1);
  });
});
