import { focusColor, focusBg, focusLabel, type LearnSegment } from "../../fixtures";

interface SegmentModalProps {
  segment: LearnSegment;
  role: "instructor" | "student";
  onClose: () => void;
  /** 클립 탭의 플레이어로 이동한다. 배선되지 않은 역할(강사 목업)에서는 버튼을 숨긴다. */
  onGoToClip?: (seconds: number) => void;
}

/** fixture 의 "MM:SS"·"H:MM:SS" 시각 문자열을 초로 바꾼다. */
const secondsOfLabel = (label: string): number =>
  label.split(":").reduce((total, part) => total * 60 + Number(part), 0);

/** 타임라인 구간 상세 모달. 집중 평가 · 구간 설명 · 복습 클립 바로가기. */
export function SegmentModal({ segment, role, onClose, onGoToClip }: SegmentModalProps) {
  const score = role === "instructor" ? segment.fAll : segment.fMine;
  const c = focusColor(score);
  const ev = role === "instructor" ? segment.evAll : segment.evMine;
  const scopeLabel = role === "instructor" ? "전체 집중도" : "내 집중도";

  return (
    <div onClick={onClose} className="z-backdrop z-[80] animate-[zPop_.16s] bg-[rgba(24,28,52,.5)]">
      <div
        onClick={(e) => e.stopPropagation()}
        className="max-h-[88vh] w-full max-w-[560px] overflow-y-auto rounded-[20px] bg-surface shadow-[0_30px_70px_rgba(24,28,52,.4)]"
      >
        <div className="relative px-[26px] pb-1 pt-6">
          <button
            onClick={onClose}
            className="absolute right-[18px] top-[18px] size-8 cursor-pointer rounded-[9px] border-0 bg-primary-softer text-[15px] text-ink-faint"
          >
            ✕
          </button>
          <div className="mb-1.5 text-[12.5px] font-extrabold text-[#3bbd8b]">
            선택 구간 · {segment.range}
          </div>
          <div className="text-[21px] font-extrabold tracking-[-.4px] text-ink">{segment.title}</div>
        </div>

        <div className="px-[26px] pb-[26px] pt-[18px]">
          <div className="z-box mb-4 px-[18px] py-4">
            <div className="mb-3 flex items-center justify-between gap-2.5">
              <span className="text-[12.5px] font-extrabold text-ink-faint">{scopeLabel} 평가</span>
              <span className="inline-flex items-baseline gap-1" style={{ color: c }}>
                <b className="text-[26px] font-black tracking-[-.5px]">{score}</b>
                <span className="text-[13px] font-extrabold text-ink-fainter">/ 4</span>
              </span>
            </div>
            <div className="mb-[11px] h-[9px] overflow-hidden rounded-full bg-[#eef0f6]">
              <div
                className="h-full rounded-full"
                style={{ width: `${(score / 4) * 100}%`, background: c }}
              />
            </div>
            <div className="flex items-center gap-[9px]">
              <span
                className="inline-block rounded-full px-2.5 py-[3px] text-[11.5px] font-extrabold"
                style={{ color: c, background: focusBg(score) }}
              >
                {focusLabel(score)}
              </span>
              <span className="flex-1 text-[12.5px] leading-[1.5] text-ink-sub">{ev}</span>
            </div>
          </div>

          <div className="mb-2 text-[13.5px] font-extrabold text-ink">이 구간 설명</div>
          <p className="mb-[22px] text-[13.5px] leading-[1.7] text-ink-sub">{segment.desc}</p>

          {onGoToClip !== undefined && (
            <button
              type="button"
              onClick={() => {
                // 모달을 닫아야 뒤에서 전환된 클립 탭이 보인다.
                onGoToClip(secondsOfLabel(segment.seek));
                onClose();
              }}
              className="z-btn z-btn-primary w-full rounded-[13px] py-3.5 text-[14.5px]"
            >
              ↗ {role === "instructor" ? "수업 클립" : "복습 클립"} 바로가기
            </button>
          )}
        </div>
      </div>
    </div>
  );
}
