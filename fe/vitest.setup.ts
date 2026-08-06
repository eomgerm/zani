import '@testing-library/jest-dom/vitest';

// jsdom 에는 ResizeObserver 가 없다. 참가자 그리드 배치기가 컨테이너를 이걸로 재기 때문에,
// 스텁이 없으면 강의실 관련 테스트가 렌더 단계에서 통째로 죽는다. 크기는 0 으로 보고되고
// 배치 계산은 그 값으로 돌아가므로, 테스트는 배치 결과가 아니라 렌더된 타일만 본다.
if (!('ResizeObserver' in globalThis)) {
  globalThis.ResizeObserver = class {
    observe() {}
    unobserve() {}
    disconnect() {}
  };
}
