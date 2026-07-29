"use client";

import { useEffect, useState } from "react";

/**
 * 숨겨진 탭에는 프롬프트를 띄우지 않는다(기준 문서 §4.3). 안 보이는 화면에 띄워봐야 학생은
 * 보지 못한 채 30초가 지나 무응답으로 닫히고, 쿨타임만 깎인다.
 */
export function isTabHidden(): boolean {
  return typeof document !== "undefined" && document.visibilityState === "hidden";
}

/**
 * 탭이 다시 보이게 된 횟수. 숨어 있는 동안 미뤄둔 발동 조건을 다시 검사하도록,
 * 이 값을 의존성에 넣어 효과를 재실행시키는 용도다.
 */
export function useTabVisibleTick(): number {
  const [tick, setTick] = useState(0);

  useEffect(() => {
    if (typeof document === "undefined") return;

    const onVisibilityChange = () => {
      if (document.visibilityState === "visible") setTick((value) => value + 1);
    };

    document.addEventListener("visibilitychange", onVisibilityChange);
    return () => document.removeEventListener("visibilitychange", onVisibilityChange);
  }, []);

  return tick;
}
