/**
 * 집중 흐름 그래프를 수업 내용 구간으로 나눠 그리기 위한 재료.
 *
 * <p>한 줄로 이어 그리면 어느 대목에서 흐름이 내려갔는지 시간축을 눈으로 되짚어야 한다. 구간마다
 * 계열을 따로 두고 그 구간 평균 색으로 칠하면, 색만 보고 "어느 내용에서 낮았는지"를 읽을 수 있다.
 *
 * <p>구간은 서버가 준다(248 의 내용 타임라인). 화면이 시간을 임의로 쪼개지 않는다.
 */

/** 차트가 필요한 만큼만 받는다 — 제목·평균까지 다 받으면 이 파일이 카드 렌더링과 얽힌다. */
export interface FlowSection {
  readonly startSeconds: number;
  readonly endSeconds: number;
  readonly focusLevel: number | null;
}

interface FlowPoint {
  readonly offsetSeconds: number;
  readonly focusLevel: number | null;
}

/** 구간 하나가 차지하는 계열 이름. recharts 는 행 안의 키로 계열을 찾는다. */
export const sectionKeyOf = (index: number): string => `section${index}`;

/** 구간 배지의 배경. 같은 구간이면 차트 색과 짝이 맞아야 눈으로 이을 수 있다. */
export const sectionSoftColorOf = (focusLevel: number | null): string => {
  if (focusLevel === null) return "#f4f5fa";
  if (focusLevel >= 3) return "#eaf7f2";
  if (focusLevel >= 2) return "#fdf6df";
  if (focusLevel >= 1) return "#fdefe8";
  return "#fdeeee";
};

/**
 * 구간 평균 색. 값이 없는 구간은 회색이다 — 1단계로 칠하면 "낮았다"로 읽힌다(REPORT-S-007).
 */
export const sectionColorOf = (focusLevel: number | null): string => {
  if (focusLevel === null) return "#c9cdde";
  if (focusLevel >= 3) return "#16c582";
  if (focusLevel >= 2) return "#f4c325";
  if (focusLevel >= 1) return "#f08c3e";
  return "#e0455f";
};

/**
 * 관측 점들을 구간별 계열로 흩는다.
 *
 * <p>경계 시각의 점은 앞뒤 두 구간에 모두 넣는다. 한쪽에만 두면 구간이 바뀌는 자리에서 그림이
 * 끊겨 실제로는 이어진 흐름이 빈 것처럼 보인다.
 */
export function toSectionRows(
  points: readonly FlowPoint[],
  sections: readonly FlowSection[],
): Record<string, number | null>[] {
  return points.map((point) => {
    const row: Record<string, number | null> = { offsetSeconds: point.offsetSeconds };
    sections.forEach((section, index) => {
      const inside =
        point.offsetSeconds >= section.startSeconds && point.offsetSeconds <= section.endSeconds;
      if (inside) row[sectionKeyOf(index)] = point.focusLevel;
    });
    return row;
  });
}

/** 구간이 갈리는 시각. 첫 구간의 시작은 축의 왼쪽 끝이라 선을 그리지 않는다. */
export function sectionBounds(sections: readonly FlowSection[]): number[] {
  return sections.slice(1).map((section) => section.startSeconds);
}

/** 구간 이름을 놓을 가운데 시각. */
export const sectionMidpoint = (section: FlowSection): number =>
  (section.startSeconds + section.endSeconds) / 2;

/** 주어진 시각이 든 구간. 상태 막대에서 고른 자리를 차트에서도 짚어 주는 데 쓴다. */
export function sectionIndexAt(
  sections: readonly FlowSection[],
  offsetSeconds: number,
): number | null {
  const index = sections.findIndex(
    (section) => offsetSeconds >= section.startSeconds && offsetSeconds <= section.endSeconds,
  );
  return index === -1 ? null : index;
}

/**
 * 차트 위에 놓는 `구간 N` 이름표. recharts `ReferenceLine` 의 label 로 넘긴다.
 *
 * <p>고른 구간만 진하게 둔다. 모두 진하면 어디를 보고 있는지 알 수 없고, 모두 옅으면 상태 막대의
 * 선택이 차트와 이어지지 않는다.
 */
export const sectionCallout = (n: number, active: boolean) => {
  const Callout = ({ viewBox }: { viewBox?: { x?: number } }) => (
    <text
      x={viewBox?.x ?? 0}
      y={14}
      textAnchor="middle"
      fill={active ? "#10b981" : "#8a90b4"}
      fontSize={11.5}
      fontWeight={800}
    >
      구간 {n}
    </text>
  );
  Callout.displayName = `SectionCallout${n}`;
  return Callout;
};
