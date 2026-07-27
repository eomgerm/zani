/// <reference lib="webworker" />

import { createAttentionModel, type AttentionModel } from "./attentionModel";
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
 * 모델은 한 번만 로드한다. 로드가 실패하면 실패를 기억해 매 창마다 재시도하지 않고
 * 판정을 비활성화한다(수업은 계속된다).
 */
let modelPromise: Promise<AttentionModel> | null = null;

function loadModel(): Promise<AttentionModel> {
  modelPromise ??= createAttentionModel();
  return modelPromise;
}

scope.onmessage = (event: MessageEvent<AttentionWorkerRequest>) => {
  const request = event.data;
  if (request.type !== "predict") return;

  void (async () => {
    let model: AttentionModel;
    try {
      model = await loadModel();
    } catch (error) {
      reply({
        type: "failure",
        requestId: request.requestId,
        kind: "modelUnavailable",
        message: reason(error, "참여도 모델을 불러오지 못했습니다."),
      });
      return;
    }

    try {
      reply({
        type: "prediction",
        requestId: request.requestId,
        prediction: await model.predict(request.tokens),
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
