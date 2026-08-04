"use client";

import { useCallback, useEffect, useRef, useState } from "react";

import type { StudentReportRequester } from "../infrastructure/studentReportApi";
import { ReportPlayer, type SeekRequest } from "./ReportPlayer";
import { TranscriptTimeline } from "./TranscriptTimeline";
import { useStudentReport } from "./useStudentReport";

const Notice = ({
  icon,
  title,
  detail,
  action,
}: {
  icon: string;
  title: string;
  detail?: string;
  action?: React.ReactNode;
}) => (
  <div className="z-card rounded-2xl px-5 py-[70px] text-center text-ink-fainter">
    <div className="mb-3.5 text-[44px]">{icon}</div>
    <div className="mb-1 font-bold text-ink-muted">{title}</div>
    {detail !== undefined && <div className="text-[13.5px]">{detail}</div>}
    {action}
  </div>
);

export interface StudentReportClipProps {
  readonly sessionId: string;
  readonly title: string;
  /** 리포트 탭(추천 카드·타임라인)에서 넘어온 이동 명령. 탭 전환과 함께 도착한다. */
  readonly seekRequest?: SeekRequest | null;
  /** 테스트에서 갈아끼우기 위한 선택 인자. 기본값이 실제 어댑터다. */
  readonly request?: StudentReportRequester;
}

/**
 * 학생 복습 클립 패널 — 공통 녹화 플레이어 + 실명 화자 전사(REPORT-S-001).
 *
 * <p>이동 명령의 합류 지점이다. 전사 행 클릭(안)과 추천 카드·타임라인(밖) 모두 여기의
 * `seekTo` 로 모여 하나의 `SeekRequest` 흐름으로 플레이어에 내려간다. 밖에서 온 명령도
 * 내부 nonce 로 다시 찍는다 — 두 소스의 nonce 가 섞이면 증가가 보장되지 않는다.
 *
 * <p>전사 하이라이트에는 재생 위치를 **정수 초로 낮춰** 전달한다. timeupdate 는 초당 네 번쯤
 * 오는데, 세 시간 수업의 전사는 수천 행이라 그 빈도로 목록을 다시 그리면 재생이 버벅인다.
 * 행 강조는 초 단위보다 촘촘할 이유가 없다.
 */
export function StudentReportClip({
  sessionId,
  title,
  seekRequest = null,
  request,
}: StudentReportClipProps) {
  const { status, report, retry, reissueRecordingUrl } = useStudentReport({ sessionId, request });

  const [seek, setSeek] = useState<SeekRequest | null>(null);
  const nonceRef = useRef(0);
  const seekTo = useCallback((seconds: number) => {
    nonceRef.current += 1;
    setSeek({ seconds, nonce: nonceRef.current });
  }, []);

  useEffect(() => {
    if (seekRequest !== null) seekTo(seekRequest.seconds);
  }, [seekRequest, seekTo]);

  const [cursorSeconds, setCursorSeconds] = useState(0);
  const handleTimeChange = useCallback((seconds: number) => {
    setCursorSeconds((prev) => {
      const next = Math.floor(seconds);
      return next === prev ? prev : next;
    });
  }, []);

  if (status === "loading") {
    return <Notice icon="⏳" title="다시 보기를 불러오는 중이에요" />;
  }

  if (status === "forbidden") {
    return (
      <Notice
        icon="🔒"
        title="이 수업의 다시 보기를 볼 수 없어요"
        detail="내가 참여한 수업이 맞는지 확인해 주세요."
      />
    );
  }

  if (status === "notReady") {
    return (
      <Notice
        icon="⏳"
        title="아직 분석이 끝나지 않았어요"
        detail="분석이 완료되면 녹화와 전사를 볼 수 있어요."
      />
    );
  }

  if (status === "failed" || report === null) {
    return (
      <Notice
        icon="⚠️"
        title="다시 보기를 불러오지 못했어요"
        action={
          <button type="button" onClick={retry} className="z-btn z-btn-outline z-btn-md mt-3">
            다시 시도
          </button>
        }
      />
    );
  }

  return (
    <div className="grid grid-cols-[1.35fr_1fr] items-stretch gap-5">
      <ReportPlayer
        /* 재조회로 URL 이 바뀌면 리마운트해 실패·재발급 이력을 처음부터 다시 시작한다. */
        key={report.recordingUrl ?? "no-recording"}
        recordingUrl={report.recordingUrl}
        title={title}
        initialSeconds={report.seekTimestamp}
        seekRequest={seek}
        onTimeChange={handleTimeChange}
        reissueUrl={reissueRecordingUrl}
      />
      {/* 전사 패널은 플레이어 높이에 맞춰 안에서만 스크롤한다(프로토타입과 같은 배치). */}
      <div className="relative min-h-[220px]">
        <div className="absolute inset-0">
          <TranscriptTimeline
            segments={report.transcript}
            currentSeconds={cursorSeconds}
            onSeek={seekTo}
          />
        </div>
      </div>
    </div>
  );
}
