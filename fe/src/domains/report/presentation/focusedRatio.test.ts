import { describe, expect, it } from "vitest";

import { focusedIntervalRatio, focusedRatioBand, FOCUSED_LEVEL } from "./focusedRatio";

const points = (...levels: (number | null)[]) =>
  levels.map((focusLevel, index) => ({ offsetSeconds: index * 30, focusLevel }));

describe("focusedIntervalRatio", () => {
  it("2.5 단계 이상인 구간의 비율을 낸다", () => {
    expect(focusedIntervalRatio(points(3, 3, 1, 1))).toBe(50);
  });

  it("경계값 2.5 는 집중한 것으로 센다", () => {
    expect(FOCUSED_LEVEL).toBe(2.5);
    expect(focusedIntervalRatio(points(2.5))).toBe(100);
    expect(focusedIntervalRatio(points(2.49))).toBe(0);
  });

  it("인원이 모자라 감춘 구간은 분모에도 넣지 않는다", () => {
    // 넷 중 둘이 null. 남은 둘은 모두 집중이므로 100% 다 — null 을 분모에 넣으면 50% 로 떨어져
    // 사람이 적었다는 이유만으로 강사가 자기 수업을 잘못 읽는다.
    expect(focusedIntervalRatio(points(3, null, null, 4))).toBe(100);
  });

  it("잴 수 있는 구간이 하나도 없으면 0% 가 아니라 null 이다", () => {
    expect(focusedIntervalRatio(points(null, null))).toBeNull();
    expect(focusedIntervalRatio([])).toBeNull();
  });

  it("정말 아무도 집중하지 않았으면 0 을 준다", () => {
    // 위의 null 과 갈려야 하는 값이다. 둘 다 null 이면 화면이 두 사실을 구분할 수 없다.
    expect(focusedIntervalRatio(points(1, 2))).toBe(0);
  });

  it("정수로 반올림한다", () => {
    expect(focusedIntervalRatio(points(3, 3, 1))).toBe(67);
  });
});

describe("focusedRatioBand", () => {
  it("80 이상은 좋음, 60 이상은 보통, 그 아래는 낮음이다", () => {
    expect(focusedRatioBand(80)).toBe("좋음");
    expect(focusedRatioBand(79)).toBe("보통");
    expect(focusedRatioBand(78)).toBe("보통");
    expect(focusedRatioBand(60)).toBe("보통");
    expect(focusedRatioBand(59)).toBe("낮음");
    expect(focusedRatioBand(0)).toBe("낮음");
  });
});
