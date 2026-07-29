/**
 * 카메라 안내의 "못 켜요" 억제 상태 저장소.
 *
 * 억제는 수업이 끝날 때까지 유효해야 하는데(기준 문서 §5.2) 메모리에만 두면 새로고침 한 번에
 * 풀려 1분 뒤 다시 뜬다. 탭을 닫으면 사라지는 `sessionStorage` 의 수명이 수업 단위와 맞다.
 * 집계에 쓰이지 않는 값이라 서버로 보낼 이유는 없다.
 */

/** 억제 상태의 sessionStorage 키. 수업별로 따로 잡아 다른 수업에 옮겨붙지 않게 한다. */
export function cameraGuideSuppressionKey(sessionId: string): string {
  return `zani:camera-guide-suppressed:${sessionId}`;
}

/** presentation 이 의존하는 계약. 테스트는 이 자리에 가짜 저장소를 넣는다. */
export interface CameraGuideSuppressionStore {
  isSuppressed(sessionId: string): boolean;
  suppress(sessionId: string): void;
}

export const sessionStorageCameraGuideSuppression: CameraGuideSuppressionStore = {
  /** 저장소 접근이 막힌 환경(사생활 보호 모드 등)에서는 억제하지 않는 쪽으로 떨어진다. */
  isSuppressed(sessionId) {
    try {
      return sessionStorage.getItem(cameraGuideSuppressionKey(sessionId)) === "1";
    } catch {
      return false;
    }
  },

  /** 저장에 실패해도 화면이 살아 있는 동안은 훅의 메모리 상태로 억제된다. */
  suppress(sessionId) {
    try {
      sessionStorage.setItem(cameraGuideSuppressionKey(sessionId), "1");
    } catch {
      // 저장 실패가 수업 화면을 막아서는 안 된다.
    }
  },
};
