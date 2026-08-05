"use client";

import { useCallback, useEffect, useRef, useState, type ReactNode } from "react";

import { PictoClockMuted, PictoLock, PictoWarn } from "@/shared/ui";
import type { StudentClipRequester } from "../infrastructure/studentClipApi";
import { ReportPlayer, type SeekRequest } from "./ReportPlayer";
import { TranscriptTimeline } from "./TranscriptTimeline";
import { useStudentClip } from "./useStudentClip";

const Notice = ({
  icon,
  title,
  detail,
  action,
}: {
  icon: ReactNode;
  title: string;
  detail?: string;
  action?: ReactNode;
}) => (
  <div className="z-card rounded-2xl px-5 py-[70px] text-center text-ink-fainter">
    <div className="mb-3.5 flex justify-center">{icon}</div>
    <div className="mb-1 font-bold text-ink-muted">{title}</div>
    {detail !== undefined && <div className="text-[13.5px]">{detail}</div>}
    {action}
  </div>
);

/**
 * 바깥에서 들어오는 이동 요청. 리포트 탭의 구간 상세가 "클립 바로가기"로 보낸다.
 *
 * <p>같은 시각을 연달아 눌러도 두 번째가 묻히지 않도록 부르는 쪽이 nonce 를 올려 준다.
 */
export interface ClipSeekRequest {
  readonly seconds: number;
  readonly nonce: number;
}

export interface StudentReportClipProps {
  readonly sessionId: string;
  readonly title: string;
  /** 테스트에서 갈아끼우기 위한 선택 인자. 기본값이 실제 어댑터다. */
  readonly request?: StudentClipRequester;
  /** 리포트 탭에서 넘어온 이동 요청. 전사 행 클릭과 같은 `seekTo` 로 합류한다. */
  readonly seekRequest?: ClipSeekRequest | null;
}

/**
 * 학생 복습 클립 패널 — 공통 녹화 플레이어 + 실명 화자 전사(REPORT-S-001).
 *
 * <p>강사 수업 클립({@code InstructorReportClip})도 requester 만 강사 엔드포인트로 바꿔 이 패널을
 * 그대로 쓴다 — 상태 안내·플레이어·전사 배선이 역할과 무관해서다.
 *
 * <p>이동 명령의 합류 지점이다. 지금은 전사 행 클릭 하나뿐이지만, 모든 명령은 `seekTo` 로
 * 모여 하나의 `SeekRequest` 흐름으로 플레이어에 내려간다 — nonce 를 한 곳에서 찍어야
 * 증가가 보장되고, 같은 시각을 연속으로 눌러도 두 번째가 무시되지 않는다.
 *
 * <p>전사 하이라이트에는 재생 위치를 **정수 초로 낮춰** 전달한다. timeupdate 는 초당 네 번쯤
 * 오는데, 세 시간 수업의 전사는 수천 행이라 그 빈도로 목록을 다시 그리면 재생이 버벅인다.
 * 행 강조는 초 단위보다 촘촘할 이유가 없다.
 */
export function StudentReportClip({
  sessionId,
  title,
  request,
  seekRequest = null,
}: StudentReportClipProps) {
  const { status, clip, retry, reissueRecordingUrl } = useStudentClip({ sessionId, request });

  const [seek, setSeek] = useState<SeekRequest | null>(null);
  const nonceRef = useRef(0);
  const seekTo = useCallback((seconds: number) => {
    nonceRef.current += 1;
    setSeek({ seconds, nonce: nonceRef.current });
  }, []);

  // 바깥 요청도 같은 문으로 들여보낸다 — nonce 를 여기서 다시 찍어 증가가 한 곳에서만 일어난다.
  useEffect(() => {
    if (seekRequest === null) return;
    seekTo(seekRequest.seconds);
  }, [seekRequest, seekTo]);

  const [cursorSeconds, setCursorSeconds] = useState(0);
  const handleTimeChange = useCallback((seconds: number) => {
    setCursorSeconds((prev) => {
      const next = Math.floor(seconds);
      return next === prev ? prev : next;
    });
  }, []);

  if (status === "loading") {
    return <Notice icon={<PictoClockMuted size={44} />} title="다시 보기를 불러오는 중이에요" />;
  }

  if (status === "forbidden") {
    return (
      <Notice
        icon={<PictoLock size={44} />}
        title="이 수업의 다시 보기를 볼 수 없어요"
        detail="내가 참여한 수업이 맞는지 확인해 주세요."
      />
    );
  }

  if (status === "notReady") {
    return (
      <Notice
        icon={<PictoClockMuted size={44} />}
        title="아직 분석이 끝나지 않았어요"
        detail="분석이 완료되면 녹화와 전사를 볼 수 있어요."
      />
    );
  }

  if (status === "failed" || clip === null) {
    return (
      <Notice
        icon={<PictoWarn size={44} />}
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
        key={clip.recordingUrl ?? "no-recording"}
        recordingUrl={clip.recordingUrl}
        title={title}
        initialSeconds={clip.seekTimestamp}
        seekRequest={seek}
        onTimeChange={handleTimeChange}
        reissueUrl={reissueRecordingUrl}
      />
      {/* 전사 패널은 플레이어 높이에 맞춰 안에서만 스크롤한다(프로토타입과 같은 배치). */}
      <div className="relative min-h-[220px]">
        <div className="absolute inset-0">
          <TranscriptTimeline
            segments={clip.transcript}
            currentSeconds={cursorSeconds}
            onSeek={seekTo}
          />
        </div>
      </div>
    </div>
  );
}
