import { focusColor, focusBg, type LearnSegment } from "../../fixtures";

interface TimelineSegmentsProps {
  segments: LearnSegment[];
  role: "instructor" | "student";
  activeSeg: number;
  onSelect: (i: number) => void;
}

/** 리포트 타임라인 구간 카드 가로 스크롤. 구간을 누르면 상세 모달을 연다. */
export function TimelineSegments({ segments, role, activeSeg, onSelect }: TimelineSegmentsProps) {
  return (
    <div className="flex gap-2.5 overflow-x-auto pb-1.5">
      {segments.map((s, i) => {
        const score = role === "instructor" ? s.fAll : s.fMine;
        const active = i === activeSeg;
        const c = focusColor(score);
        return (
          <div
            key={i}
            onClick={() => onSelect(i)}
            className={`w-[168px] shrink-0 cursor-pointer rounded-[13px] border px-3.5 py-[13px] ${
              active ? "border-line-primary bg-primary-softer" : "border-line-light bg-surface"
            }`}
          >
            <div className="mb-[9px] flex items-center justify-between">
              <span className="font-mono text-[11px] font-bold text-ink-fainter">{s.range}</span>
              <span
                className="flex size-[26px] items-center justify-center rounded-full border-[1.5px] text-xs font-black"
                style={{ color: c, background: focusBg(score), borderColor: c }}
              >
                {score}
              </span>
            </div>
            <div className="text-[12.5px] font-extrabold leading-[1.4] text-[#3a3f5c]">
              {s.title}
            </div>
          </div>
        );
      })}
    </div>
  );
}
