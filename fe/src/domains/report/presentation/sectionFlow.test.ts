import { describe, expect, it } from "vitest";

import {
  sectionBounds,
  sectionColorOf,
  sectionIndexAt,
  sectionKeyOf,
  sectionMidpoint,
  toSectionRows,
} from "./sectionFlow";

const section = (startSeconds: number, endSeconds: number, focusLevel: number | null = 3) => ({
  startSeconds,
  endSeconds,
  focusLevel,
});

const point = (offsetSeconds: number, focusLevel: number | null) => ({
  offsetSeconds,
  focusLevel,
});

describe("sectionColorOf", () => {
  it("구간 평균이 낮아질수록 다른 색을 준다", () => {
    expect(sectionColorOf(3.4)).not.toBe(sectionColorOf(2.4));
    expect(sectionColorOf(2.4)).not.toBe(sectionColorOf(1.4));
  });

  /** 값이 없는 구간을 1단계 색으로 칠하면 "낮았다"로 읽힌다(REPORT-S-007). */
  it("값이 없는 구간은 가장 낮은 단계와 다른 색이다", () => {
    expect(sectionColorOf(null)).not.toBe(sectionColorOf(1));
  });
});

describe("toSectionRows", () => {
  it("점을 자기 구간 계열에 넣는다", () => {
    const rows = toSectionRows(
      [point(0, 2), point(10, 3), point(60, 4), point(70, 4)],
      [section(0, 30), section(31, 90)],
    );

    expect(rows[0][sectionKeyOf(0)]).toBe(2);
    expect(rows[3][sectionKeyOf(1)]).toBe(4);
    // 이음매(구간의 첫·마지막 점)가 아닌 자리는 남의 구간에 끼지 않는다.
    expect(rows[0][sectionKeyOf(1)]).toBeUndefined();
    expect(rows[3][sectionKeyOf(0)]).toBeUndefined();
  });

  /** 구간 사이가 1초라도 벌어지면 그 틈에 점이 없어 선이 끊긴다. */
  it("구간 사이가 떨어져 있어도 이음매에서 선이 이어진다", () => {
    const rows = toSectionRows([point(0, 2), point(60, 4)], [section(0, 30), section(31, 90)]);

    // 앞 구간의 마지막 점에 뒤 구간의 첫 값이, 그 반대도 한 번씩 얹힌다.
    expect(rows[0][sectionKeyOf(1)]).toBe(4);
    expect(rows[1][sectionKeyOf(0)]).toBe(2);
  });

  /** 경계 점을 한쪽에만 두면 구간이 바뀌는 자리에서 그림이 끊긴다. */
  it("경계에 걸친 점은 앞뒤 구간에 모두 넣는다", () => {
    const rows = toSectionRows([point(30, 3)], [section(0, 30), section(30, 60)]);

    expect(rows[0][sectionKeyOf(0)]).toBe(3);
    expect(rows[0][sectionKeyOf(1)]).toBe(3);
  });

  it("값이 없는 점은 값 없이 그대로 둔다 — 0 으로 채우지 않는다", () => {
    const rows = toSectionRows([point(10, null)], [section(0, 30)]);

    expect(rows[0][sectionKeyOf(0)]).toBeNull();
  });

  it("시각은 그대로 남겨 축이 읽을 수 있게 한다", () => {
    const rows = toSectionRows([point(10, 2)], [section(0, 30)]);

    expect(rows[0].offsetSeconds).toBe(10);
  });
});

describe("sectionBounds", () => {
  /** 첫 구간의 시작은 축의 왼쪽 끝이라 선을 그리면 축과 겹친다. */
  it("첫 구간의 시작은 경계로 세지 않는다", () => {
    expect(sectionBounds([section(0, 30), section(30, 60), section(60, 90)])).toEqual([30, 60]);
  });

  it("구간이 하나면 경계가 없다", () => {
    expect(sectionBounds([section(0, 30)])).toEqual([]);
  });
});

describe("sectionMidpoint", () => {
  it("구간의 가운데 시각을 준다", () => {
    expect(sectionMidpoint(section(0, 90))).toBe(45);
  });
});

describe("sectionIndexAt", () => {
  it("그 시각이 든 구간을 찾는다", () => {
    expect(sectionIndexAt([section(0, 30), section(31, 90)], 40)).toBe(1);
  });

  it("어느 구간에도 없으면 아무것도 고르지 않는다", () => {
    expect(sectionIndexAt([section(0, 30)], 100)).toBeNull();
  });
});
