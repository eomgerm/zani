"use client";

import { useEffect, useRef } from "react";

import type { StudentTimelineState } from "../infrastructure/attentionTimelineApi";
import { nextSegmentIndex, type TrackSegment } from "./timelineTrack";

/**
 * 상태 막대. 타임라인의 상호작용은 전부 여기가 맡는다(NFR-UX-007).
 *
 * <p>차트 SVG 안에는 포커스 요소를 만들지 않는다. recharts 가 그리는 path 는 키보드로 짚을 수
 * 없고, 억지로 tabindex 를 넣어도 스크린리더가 읽을 이름이 없다.
 *
 * <p>상태는 색 + 텍스트 + 패턴 3중으로 구분한다(NFR-UX-005). 색만 쓰면 색각 이상인 사람에게
 * 막대가 통짜 회색으로 보인다.
 */

/** `null` 은 "관측이 없었다"다. 0 도, 옆 구간의 연장도 아니다(REPORT-S-007). */
const STATE_LABELS: Record<string, string> = {
  GOOD: "집중",
  CHECK_NEEDED: "확인 필요",
  CAMERA_OFF: "카메라 꺼짐",
  UNMEASURABLE: "측정 불가",
  NONE: "기록 없음",
};

/**
 * 패턴은 CSS `repeating-linear-gradient` 로 낸다. 여기는 DOM 이라 SVG `<pattern>` 을 쓸 수 없고,
 * 차트 안(SVG)에서는 반대로 이 값을 쓸 수 없다.
 */
const STATE_STYLES: Record<string, { readonly background: string; readonly border: string }> = {
  GOOD: { background: "#16c582", border: "#0f9c68" },
  CHECK_NEEDED: {
    // 촘촘한 사선
    background:
      "repeating-linear-gradient(45deg, #f4c325 0 3px, #d9a800 3px 6px)",
    border: "#b98c00",
  },
  CAMERA_OFF: {
    // 넓은 사선
    background:
      "repeating-linear-gradient(45deg, #e0714f 0 6px, #b4502f 6px 12px)",
    border: "#8f3d21",
  },
  UNMEASURABLE: {
    // 점선
    background:
      "repeating-linear-gradient(90deg, #8a90b4 0 3px, transparent 3px 7px), #dfe2ee",
    border: "#8a90b4",
  },
  NONE: {
    // 회색 빗금
    background:
      "repeating-linear-gradient(135deg, #c9cdde 0 2px, #eef0f6 2px 8px)",
    border: "#c9cdde",
  },
};

const keyOf = (state: StudentTimelineState | null): string => state ?? "NONE";

const pad = (value: number) => String(value).padStart(2, "0");

/** 오프셋 초를 수업 경과 시각으로 읽는다. 한 시간을 넘기면 시간 자리를 붙인다. */
export function formatOffset(seconds: number): string {
  const total = Math.max(0, Math.round(seconds));
  const hours = Math.floor(total / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  const rest = total % 60;
  return hours > 0
    ? `${hours}:${pad(minutes)}:${pad(rest)}`
    : `${pad(minutes)}:${pad(rest)}`;
}

/** 학생 화면의 기본 문구. 강사 카드는 `renderLabel` 로 갈아끼운다. */
const defaultLabelOf = (segment: TrackSegment): string =>
  `${formatOffset(segment.startSeconds)}~${formatOffset(segment.endSeconds)} · ${STATE_LABELS[keyOf(segment.state)]}`;

export interface TimelineStatusBarProps {
  readonly segments: readonly TrackSegment[];
  readonly selectedIndex: number;
  readonly onSelect: (index: number) => void;
  readonly label: string;
  /**
   * 구간 하나의 문구를 카드가 정한다. 없으면 학생 화면의 상태 라벨을 쓴다.
   * 강사 카드는 학생 상태가 아니라 흐트러짐 구간을 담기 때문에 필요하다.
   */
  readonly renderLabel?: (segment: TrackSegment) => string;
}

export function TimelineStatusBar({
  segments,
  selectedIndex,
  onSelect,
  label,
  renderLabel,
}: TimelineStatusBarProps) {
  const listRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    const list = listRef.current;
    if (list === null) return;
    // 이미 막대 안에 포커스가 있을 때만 옮긴다. 마운트만으로 포커스를 뺏으면
    // 화면을 연 사람이 읽던 자리를 잃는다.
    if (!list.contains(document.activeElement)) return;
    const target = list.children[selectedIndex];
    if (target instanceof HTMLElement) target.focus();
  }, [selectedIndex]);

  const labelOf = renderLabel ?? defaultLabelOf;
  const selected = segments[selectedIndex];

  const onKeyDown = (event: React.KeyboardEvent<HTMLDivElement>) => {
    const next = nextSegmentIndex(selectedIndex, event.key, segments.length);
    if (next === selectedIndex) return;
    event.preventDefault();
    onSelect(next);
  };

  // 상태별 범례는 실제로 나온 상태만 낸다. 없는 상태를 설명하면 읽을 것만 늘어난다.
  const presentStates = renderLabel
    ? []
    : [...new Set(segments.map((segment) => keyOf(segment.state)))];

  const totalSeconds = segments.reduce(
    (sum, segment) => sum + Math.max(0, segment.endSeconds - segment.startSeconds),
    0,
  );

  return (
    <div>
      <div
        ref={listRef}
        role="group"
        aria-label={label}
        onKeyDown={onKeyDown}
        className="flex h-7 w-full overflow-hidden rounded-lg border border-line-light"
      >
        {segments.map((segment, index) => {
          const style = STATE_STYLES[keyOf(segment.state)];
          const span = Math.max(0, segment.endSeconds - segment.startSeconds);
          return (
            <button
              key={`${segment.startSeconds}-${index}`}
              type="button"
              tabIndex={index === selectedIndex ? 0 : -1}
              aria-pressed={index === selectedIndex}
              onClick={() => onSelect(index)}
              style={{
                flexGrow: totalSeconds > 0 ? span : 1,
                flexBasis: 0,
                background: style.background,
                boxShadow:
                  index === selectedIndex ? `inset 0 0 0 2.5px ${style.border}` : undefined,
              }}
              className="h-full min-w-[3px] cursor-pointer border-0 p-0 outline-offset-2"
            >
              <span className="sr-only">{labelOf(segment)}</span>
            </button>
          );
        })}
      </div>

      {presentStates.length > 0 && (
        <ul className="mt-2 flex list-none flex-wrap gap-x-3.5 gap-y-1.5 p-0 text-[11.5px] font-semibold text-ink-faint">
          {presentStates.map((state) => (
            <li key={state} className="flex items-center gap-1.5">
              <span
                aria-hidden="true"
                className="inline-block size-3 rounded-[3px] border border-line-light"
                style={{ background: STATE_STYLES[state].background }}
              />
              {STATE_LABELS[state]}
            </li>
          ))}
        </ul>
      )}

      <p aria-live="polite" className="mt-2 text-xs font-bold text-ink-sub">
        {selected === undefined ? "" : `선택한 구간 · ${labelOf(selected)}`}
      </p>
    </div>
  );
}
