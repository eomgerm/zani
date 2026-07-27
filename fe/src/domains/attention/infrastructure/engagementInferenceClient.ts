import type { EngagementPrediction } from "../domain/engagementPrediction";
import type {
  EngagementFailureKind,
  EngagementWorkerPort,
  EngagementWorkerResponse,
} from "./engagementWorkerProtocol";

/**
 * 추론 Worker 의 메인 스레드 측 창구.
 *
 * 판정은 Worker 에서 돌기 때문에 메인 스레드는 토큰을 넘기고 결과만 받는다. 추론이 한
 * 번에 하나만 돌도록 유지하고, 그 사이에 완성된 창이 여러 개 쌓이면 가장 최신 하나만
 * 남기고 나머지는 버린다(밀린 프레임 폐기).
 */

export interface EngagementInferenceFailure {
  readonly kind: EngagementFailureKind;
  readonly message: string;
}

export interface EngagementInferenceClient {
  /** 완성된 20×98 토큰 창을 넘긴다. 추론 중이면 최신 창으로 대체된다. */
  submit(tokens: Float32Array): void;
  terminate(): void;
}

export interface EngagementInferenceClientOptions {
  /** 실제 Worker 생성. 테스트에서 대체한다. */
  createWorker?: () => EngagementWorkerPort;
  onPrediction(prediction: EngagementPrediction): void;
  onFailure(failure: EngagementInferenceFailure): void;
}

function spawnWorker(): EngagementWorkerPort {
  // 번들러가 이 URL 로 Worker 청크를 따로 만들어 준다.
  return new Worker(new URL("./engagementInference.worker.ts", import.meta.url), {
    type: "module",
  }) as unknown as EngagementWorkerPort;
}

export function createEngagementInferenceClient(
  options: EngagementInferenceClientOptions,
): EngagementInferenceClient {
  const { createWorker = spawnWorker, onPrediction, onFailure } = options;

  // 모델 로드가 몇 초 걸리므로 첫 10초 창이 완성되기 전에 미리 띄워 둔다.
  const worker = createWorker();

  let nextRequestId = 1;
  /** 응답을 기다리는 요청 번호. null 이면 유휴. */
  let inFlightRequestId: number | null = null;
  /** 추론 중에 완성된 가장 최신 창. 이전 대기 창은 여기서 덮어써 버려진다. */
  let pendingTokens: Float32Array | null = null;
  let disabled = false;
  let terminated = false;

  function post(tokens: Float32Array): void {
    const requestId = nextRequestId;
    nextRequestId += 1;
    inFlightRequestId = requestId;
    // 토큰 창은 매번 새로 만들어지므로 복사 없이 소유권을 넘긴다.
    worker.postMessage({ type: "predict", requestId, tokens }, [tokens.buffer]);
  }

  function drainPending(): void {
    inFlightRequestId = null;
    if (disabled || pendingTokens === null) return;
    const latest = pendingTokens;
    pendingTokens = null;
    post(latest);
  }

  worker.onmessage = (event: MessageEvent<EngagementWorkerResponse>) => {
    if (terminated) return;
    const response = event.data;
    // 이미 대체되거나 취소된 요청의 늦은 응답은 버린다.
    if (response.requestId !== null && response.requestId !== inFlightRequestId) return;

    if (response.type === "prediction") {
      onPrediction(response.prediction);
    } else {
      if (response.kind === "modelUnavailable") {
        disabled = true;
        pendingTokens = null;
      }
      onFailure({ kind: response.kind, message: response.message });
    }
    if (disabled) {
      inFlightRequestId = null;
      return;
    }
    drainPending();
  };

  worker.onerror = (event: unknown) => {
    if (terminated) return;
    disabled = true;
    pendingTokens = null;
    inFlightRequestId = null;
    const message =
      typeof event === "object" && event !== null && "message" in event
        ? String((event as { message: unknown }).message)
        : "추론 Worker를 시작하지 못했습니다.";
    onFailure({ kind: "modelUnavailable", message });
  };

  return {
    submit(tokens: Float32Array): void {
      if (disabled || terminated) return;
      if (inFlightRequestId !== null) {
        pendingTokens = tokens;
        return;
      }
      post(tokens);
    },
    terminate(): void {
      terminated = true;
      pendingTokens = null;
      inFlightRequestId = null;
      worker.terminate();
    },
  };
}
