/// <reference lib="webworker" />

import { createAttentionModelLoader } from "./attentionModelLoader";
import type {
  AttentionWorkerRequest,
  AttentionWorkerResponse,
} from "./attentionWorkerProtocol";

/**
 * 참여도 추론 Worker.
 *
 * ONNX 세션 생성과 추론이 전부 이 스레드에서 돌기 때문에 메인 스레드의 렌더 루프가
 * 막히지 않는다. 토큰만 받고 판정 결과만 돌려주며, 서버로 나가는 요청은 만들지 않는다.
 */

const scope = self as unknown as DedicatedWorkerGlobalScope;

function reply(response: AttentionWorkerResponse): void {
  scope.postMessage(response);
}

function reason(error: unknown, fallback: string): string {
  return error instanceof Error ? error.message : fallback;
}

/**
 * 모델은 성공하면 한 번만 로드한다. 실패하면 창마다 다시 받지 않고 로더가 정해둔 횟수만
 * 타이머로 다시 시도하며, 그 횟수를 다 쓰면 판정을 비활성화한다(수업은 계속된다).
 */
const loader = createAttentionModelLoader();

scope.onmessage = (event: MessageEvent<AttentionWorkerRequest>) => {
  const request = event.data;
  if (request.type !== "predict") return;

  void (async () => {
    const loaded = await loader.load();
    if (loaded.kind !== "ready") {
      reply({
        type: "failure",
        requestId: request.requestId,
        // 재시도가 남아 있으면 판정을 끄지 않고 이 창만 건너뛴다.
        kind: loaded.kind === "exhausted" ? "modelUnavailable" : "modelLoadRetrying",
        message: loaded.message,
      });
      return;
    }

    try {
      reply({
        type: "prediction",
        requestId: request.requestId,
        prediction: await loaded.model.predict(request.tokens),
      });
    } catch (error) {
      reply({
        type: "failure",
        requestId: request.requestId,
        kind: "inferenceFailed",
        message: reason(error, "참여도 추론에 실패했습니다."),
      });
    }
  })();
};
