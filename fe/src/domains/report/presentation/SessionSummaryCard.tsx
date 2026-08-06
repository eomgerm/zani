"use client";

import { useEffect, useRef, type ReactNode } from "react";

import { PictoClockMuted, PictoLock, PictoWarn } from "@/shared/ui";
import type { ReportQuestionAsker } from "../infrastructure/reportAssistantApi";
import type {
  SessionSummaryRequester,
  SessionSummarySection,
} from "../infrastructure/sessionSummaryApi";
import { formatOffset } from "./offsetTime";
import { ReportAssistantPanel } from "./ReportAssistantPanel";
import { useReportAssistant } from "./useReportAssistant";
import { useReportSelection } from "./useReportSelection";
import { useSessionSummary } from "./useSessionSummary";

export interface SessionSummaryCardProps {
  readonly sessionId: string;
  /** 테스트에서 갈아끼우기 위한 선택 인자. 기본값이 실제 어댑터다. */
  readonly request?: SessionSummaryRequester;
  /** 질의응답 어댑터. 같은 이유로 갈아끼울 수 있게 열어 둔다. */
  readonly ask?: ReportQuestionAsker;
  /** 구간 시각이나 인용을 눌렀을 때 녹화를 그 자리로 옮긴다. 배선이 없으면 시각을 버튼으로 내지 않는다. */
  readonly onSeek?: (offsetSeconds: number) => void;
}

const Notice = ({ icon, title, detail }: { icon: ReactNode; title: string; detail?: string }) => (
  <div className="py-8 text-center text-ink-fainter">
    <div className="mb-3 flex justify-center">{icon}</div>
    <div className="mb-1 font-bold text-ink-muted">{title}</div>
    {detail !== undefined && <div className="text-[13.5px]">{detail}</div>}
  </div>
);

const RANGE_CLS = "pl-3 font-mono text-[12px] tabular-nums text-primary/70 hover:text-primary transition-colors duration-150";

/**
 * 구간 한 칸. 시각 → 제목 → 요약 순으로 읽는다.
 *
 * <p>요약이 없는 구간도 제목과 시각은 남긴다. 248 이 제목만 채운 세션이 있고, 그때도 "이 시각에 무엇을
 * 다뤘다" 는 읽을 값이다.
 *
 * <p>시각은 배선이 있을 때만 버튼이 된다 — 전사 행({@code TranscriptTimeline})과 같은 약속이다.
 * 이동을 받을 플레이어가 없는 자리에서는 누를 곳 없는 버튼을 내지 않는다.
 */
const SectionRow = ({
  section,
  onSeek,
}: {
  section: SessionSummarySection;
  onSeek?: (offsetSeconds: number) => void;
}) => {
  const range = `${formatOffset(section.startSeconds)}–${formatOffset(section.endSeconds)}`;

  return (
    // 드래그 앵커가 이 좌표를 읽는다. 선택 지점에서 위로 올라가 가장 가까운 [data-section-start-ms] 를
    // 찾는 방식이라 API 를 더 부르지 않고도 "어느 구간을 짚었는지" 가 나온다.
    <li className="pl-1" data-section-start-ms={section.startOffsetMs}>
      <div className="mb-1 flex items-baseline gap-2">
        {section.title.length > 0 && (
          <span className="font-bold text-[16.5px] text-ink-muted">
            {section.title}
            {onSeek === undefined ? (
              <span className={RANGE_CLS}>{range}</span>
            ) : (
              <button
                type="button"
                onClick={() => onSeek(section.startSeconds)}
                aria-label={`${section.title} 구간 재생 · ${range}`}
                className={`${RANGE_CLS} cursor-pointer border-0 bg-transparent underline-offset-4`}
              >
                {range}
              </button>
            )}
          </span>
        )}
      </div>
      {section.summary.length > 0 && (
        <p className="text-[13.5px] leading-[1.75] text-ink-sub">{section.summary}</p>
      )}
    </li>
  );
};

/**
 * 수업 요약 카드 — 강사·학생 공통.
 *
 * <p>역할 인자를 받지 않는 것이 이 컴포넌트의 요점이다. 요약은 두 사람이 같은 문장을 보는 공통
 * 산출물이라(FRD §21) 탭이 갈려도 이 카드는 하나다 — 강사가 "요약 두 번째 문장" 이라고 말하면
 * 학생 화면의 같은 자리를 가리켜야 한다.
 *
 * <p>카드 껍데기와 제목은 어떤 상태에서도 남긴다. 상태에 따라 카드가 사라지면 아래 내용이 위로
 * 밀려 올라가 화면이 흔들린다.
 *
 * <p>전체 문단을 먼저 두고 구간을 그 아래에 편다(S15P11A105-314). 절 구분은 화면이 지어내는 것이
 * 아니라 사후 분석이 나눈 구간이며, 구간이 없는 세션은 문단만 남는다 — 빈 목록은 오류가 아니다.
 */
export function SessionSummaryCard({ sessionId, request, ask, onSeek }: SessionSummaryCardProps) {
  const { status, summary, sections, retry } = useSessionSummary({ sessionId, request });

  // 드래그를 카드 안쪽으로 한정한다. 리포트 화면에는 전사 패널처럼 드래그할 곳이 더 있다.
  const cardRef = useRef<HTMLDivElement | null>(null);
  const { selection, clear } = useReportSelection(cardRef);
  const assistant = useReportAssistant({ sessionId, ask });
  const { askAbout } = assistant;

  /**
   * 드래그가 곧 질문이다. 무엇을 물을지 먼저 입력하게 하면, 읽다가 막힌 순간에 바로 묻는 흐름이 끊긴다.
   *
   * <p>보낸 뒤 선택을 비우는 것이 이 효과가 한 번만 도는 이유다 — `clear()` 로 selection 이 null 이 되고,
   * 다시 들어오면 위에서 끊긴다.
   */
  useEffect(() => {
    if (selection === null) return;
    askAbout({ anchorStartMs: selection.anchorStartMs, selectedText: selection.text });
    clear();
  }, [selection, askAbout, clear]);

  return (
    <div ref={cardRef} className="z-card px-7 py-6">
      <div className="z-section-title mb-4">수업 요약 레포트</div>
      {status === "loading" && (
        <Notice icon={<PictoClockMuted size={36} />} title="수업 요약을 불러오는 중이에요" />
      )}
      {status === "notReady" && (
        <Notice
          icon={<PictoClockMuted size={36} />}
          title="아직 분석이 끝나지 않았어요"
          detail="분석이 완료되면 수업 요약을 볼 수 있어요."
        />
      )}
      {status === "forbidden" && (
        <Notice
          icon={<PictoLock size={36} />}
          title="이 수업의 요약을 볼 수 없어요"
          detail="내가 참여한 수업이 맞는지 확인해 주세요."
        />
      )}
      {(status === "failed" || (status === "ready" && summary === null)) && (
        <div className="py-8 text-center text-ink-fainter">
          <div className="mb-3 flex justify-center">
            <PictoWarn size={36} />
          </div>
          <div className="mb-1 font-bold text-ink-muted">수업 요약을 불러오지 못했어요</div>
          <button type="button" onClick={retry} className="z-btn z-btn-outline z-btn-md mt-3">
            다시 시도
          </button>
        </div>
      )}
      {status === "ready" && summary !== null && (
        <>
          <p className="whitespace-pre-line text-[13.5px] leading-[1.75] text-ink-sub">{summary}</p>
          {sections.length > 0 && (
            <ul className="mt-6 flex flex-col gap-5">
              {sections.map((section) => (
                <SectionRow
                  key={`${section.startSeconds}-${section.title}`}
                  section={section}
                  onSeek={onSeek}
                />
              ))}
            </ul>
          )}
        </>
      )}

      {/* position: fixed 라 카드 안에 두어도 뷰포트 기준으로 뜬다 — z-card 에 transform 이 없다. */}
      {assistant.anchor !== null && (
        <ReportAssistantPanel
          messages={assistant.messages}
          status={assistant.status}
          failure={assistant.failure}
          onSend={assistant.send}
          onClose={assistant.close}
          onSeek={onSeek}
        />
      )}
    </div>
  );
}
