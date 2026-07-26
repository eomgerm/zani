/**
 * 입장 전 점검(prejoin) 통과 정보. 점검 화면이 sessionStorage 에 쓰고 강의실이 읽어
 * 같은 장치로 이어 들어가게 한다. 장치 원본 데이터는 저장하지 않는다.
 */
export interface PrejoinResult {
  readonly cameraDeviceId: string | null;
  readonly microphoneDeviceId: string | null;
  readonly testedAt: string;
}

/** prejoin 통과 정보의 sessionStorage 키. */
export function prejoinStorageKey(inviteCode: string): string {
  return `zani:prejoin:${inviteCode}`;
}

/** 입장 전 점검 결과를 저장한다. 저장이 막힌 환경에서는 조용히 넘어간다(입장 자체를 막지 않는다). */
export function writePrejoinResult(inviteCode: string, result: PrejoinResult): void {
  try {
    sessionStorage.setItem(prejoinStorageKey(inviteCode), JSON.stringify(result));
  } catch {
    // 저장 실패는 강의실에서 기본 장치로 진행하는 것으로 충분하다.
  }
}

/** 저장된 prejoin 통과 정보를 읽는다. 없거나 형식이 깨졌으면 null 을 준다(강의실은 기본 장치로 진행). */
export function readPrejoinResult(inviteCode: string): PrejoinResult | null {
  try {
    const raw = sessionStorage.getItem(prejoinStorageKey(inviteCode));
    if (!raw) {
      return null;
    }
    const parsed = JSON.parse(raw) as Partial<PrejoinResult>;
    if (typeof parsed.testedAt !== "string") {
      return null;
    }
    return {
      cameraDeviceId: typeof parsed.cameraDeviceId === "string" ? parsed.cameraDeviceId : null,
      microphoneDeviceId:
        typeof parsed.microphoneDeviceId === "string" ? parsed.microphoneDeviceId : null,
      testedAt: parsed.testedAt,
    };
  } catch {
    return null;
  }
}
