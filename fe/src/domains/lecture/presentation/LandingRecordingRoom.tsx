"use client";

import { useState, type MouseEvent } from "react";
import { CoachingPromptPanel } from "@/domains/attention";
import { RoomMockupScreen } from "./RoomMockupScreen";
import { AnalysisStatusNotice } from "./components/room/AnalysisStatusNotice";
import { CoachTipCard } from "./components/room/CoachTipCard";
import styles from "./LandingRecordingRoom.module.css";

export type LandingRecordingScene =
  | "live-classroom"
  | "browser-analysis"
  | "student-prompt"
  | "instructor-tip";

export function LandingRecordingRoom({ scene }: { scene: LandingRecordingScene }) {
  const [analysisPaused, setAnalysisPaused] = useState(false);
  const [promptVisible, setPromptVisible] = useState(scene === "student-prompt");
  const [tipVisible, setTipVisible] = useState(scene === "instructor-tip");

  const handleRoomClick = (event: MouseEvent<HTMLDivElement>) => {
    if (scene !== "browser-analysis") return;
    const target = event.target as HTMLElement;
    if (target.closest('button[aria-label="카메라 끄기"]')) {
      setAnalysisPaused(true);
    }
    if (target.closest('button[aria-label="카메라 켜기"]')) {
      setAnalysisPaused(false);
    }
  };

  return (
    <div className={styles.root} onClickCapture={handleRoomClick} data-recording-ready>
      <RoomMockupScreen />

      {scene === "browser-analysis" && analysisPaused ? (
        <div className={styles.analysisNotice}>
          <AnalysisStatusNotice availability="PAUSED" />
        </div>
      ) : null}

      {promptVisible ? (
        <CoachingPromptPanel
          title="잠깐 확인할게요"
          body="방금 설명한 내용, 지금 어떤가요? 응답은 강사에게 개인별로 공개되지 않아요."
          remainingMs={8_000}
          durationMs={10_000}
          onSelect={() => setPromptVisible(false)}
          options={[
            {
              value: "OK",
              label: "이해했어요",
              emoji: "👍",
              toneClassName: "border-[#d4f0e5] bg-primary-mint text-primary-dark",
            },
            {
              value: "CONFUSED",
              label: "헷갈려요",
              emoji: "🤔",
              toneClassName: "border-[#f3dc90] bg-warn-soft text-[#836607]",
            },
            {
              value: "MISSED",
              label: "놓쳤어요",
              emoji: "😅",
              toneClassName: "border-line-muted bg-primary-softer text-ink-muted",
            },
          ]}
        />
      ) : null}

      {tipVisible ? (
        <CoachTipCard
          tip={{
            tipType: "CONFUSED_AND_MISSED",
            title: "확인 질문을 한 번 던져보세요",
            message:
              "여러 학생의 익명 신호가 비슷한 구간에 모였습니다. 방금 설명한 핵심 개념을 짧게 확인해 보세요.",
            targetConcept: "상태 변경과 렌더링의 관계",
          }}
          onDismiss={() => setTipVisible(false)}
        />
      ) : null}
    </div>
  );
}
