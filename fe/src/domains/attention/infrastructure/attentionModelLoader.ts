import { createAttentionModel, type AttentionModel } from "./attentionModel";

/**
 * 모델 로드 실패 복구 정책. 값은 이 파일에서만 정의한다.
 *
 * 실패 원인 중 다수는 일시적이다(다운로드 중단·네트워크 흔들림·메모리 부족). 한 번의 실패로
 * 수업 내내 판정을 끄면 그 학생이 강사 화면의 분모에서 조용히 빠진다. 반대로 판정 창마다 12MB
 * 모델을 다시 받으면 수업 자체가 망가진다. 그래서 창 주기와 무관한 타이머로 정해진 횟수만
 * 다시 시도한다.
 */
export const MODEL_LOAD_RETRY = {
  /** 실패한 뒤 다시 받기까지 기다리는 시간. */
  delayMs: 30_000,
  /** 최초 시도 뒤 추가로 허용하는 재시도 횟수. */
  maxRetries: 3,
} as const;

const FALLBACK_MESSAGE = "참여도 모델을 불러오지 못했습니다.";

export type ModelLoadOutcome =
  | { readonly kind: "ready"; readonly model: AttentionModel }
  /** 재시도가 남았다. 이 창만 판정을 건너뛰고 다음 창은 그대로 받는다. */
  | { readonly kind: "retrying"; readonly message: string }
  /** 재시도를 다 썼다. 판정을 비활성화한다. */
  | { readonly kind: "exhausted"; readonly message: string };

export interface AttentionModelLoaderOptions {
  /** 실제 로드. 테스트에서 대체한다. */
  readonly createModel?: () => Promise<AttentionModel>;
  readonly delayMs?: number;
  readonly maxRetries?: number;
  /** 재시도 예약. 테스트에서 타이머 대신 수동 실행으로 바꾼다. */
  readonly schedule?: (callback: () => void, delayMs: number) => void;
}

export interface AttentionModelLoader {
  /**
   * 지금 판정에 쓸 수 있는 모델을 돌려준다.
   *
   * 재시도를 기다리는 동안에는 로드를 기다리지 않고 곧바로 `retrying` 을 돌려준다. 30초를
   * 기다리며 판정 루프를 붙잡으면 그 사이 완성된 창이 전부 밀려 수업 화면이 멈춘 것처럼 보인다.
   */
  load(): Promise<ModelLoadOutcome>;
}

function reason(error: unknown): string {
  return error instanceof Error ? error.message : FALLBACK_MESSAGE;
}

export function createAttentionModelLoader(
  options: AttentionModelLoaderOptions = {},
): AttentionModelLoader {
  const {
    createModel = () => createAttentionModel(),
    delayMs = MODEL_LOAD_RETRY.delayMs,
    maxRetries = MODEL_LOAD_RETRY.maxRetries,
    schedule = (callback: () => void, waitMs: number) => {
      setTimeout(callback, waitMs);
    },
  } = options;

  let model: AttentionModel | null = null;
  /** 진행 중인 로드. 같은 시도를 여러 창이 함께 기다린다. */
  let attempt: Promise<AttentionModel> | null = null;
  let failures = 0;
  /** 다음 시도까지 기다리는 중. 이 동안에는 로드를 걸지 않는다. */
  let waiting = false;
  let lastMessage = FALLBACK_MESSAGE;

  const isExhausted = () => failures > maxRetries;

  function failureOutcome(): ModelLoadOutcome {
    return isExhausted()
      ? { kind: "exhausted", message: lastMessage }
      : { kind: "retrying", message: lastMessage };
  }

  function begin(): Promise<AttentionModel> {
    const started = createModel();
    attempt = started;
    return started;
  }

  function recordFailure(error: unknown): void {
    failures += 1;
    lastMessage = reason(error);
    if (isExhausted()) return;
    waiting = true;
    schedule(() => {
      waiting = false;
      // 다음 창을 기다리지 않고 미리 받아 둔다. 그래야 복구된 창부터 바로 판정이 나온다.
      void settle(begin());
    }, delayMs);
  }

  async function settle(started: Promise<AttentionModel>): Promise<ModelLoadOutcome> {
    try {
      const loaded = await started;
      model = loaded;
      return { kind: "ready", model: loaded };
    } catch (error) {
      // 같은 시도를 기다린 창이 여러 개여도 실패는 한 번만 센다.
      if (attempt === started) {
        attempt = null;
        recordFailure(error);
      }
      return failureOutcome();
    }
  }

  return {
    async load(): Promise<ModelLoadOutcome> {
      if (model !== null) return { kind: "ready", model };
      if (isExhausted() || waiting) return failureOutcome();
      return settle(attempt ?? begin());
    },
  };
}
