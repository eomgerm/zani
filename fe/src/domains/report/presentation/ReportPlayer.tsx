"use client";

import { useEffect, useRef, useState, type ReactNode } from "react";

import { PictoClockMuted, PictoWarn } from "@/shared/ui";
import { formatOffset } from "./offsetTime";

/**
 * 외부(전사 행·복습 추천·타임라인)에서 온 이동 명령. `nonce` 가 있어야 같은 시각을 연속으로
 * 두 번 눌러도 두 번째가 무시되지 않는다 — 값만 보면 두 명령이 구분되지 않는다.
 */
export type SeekRequest = { readonly seconds: number; readonly nonce: number };

export interface ReportPlayerProps {
  /** 권한 검증 단기 접근 URL. `null` 은 녹화가 아직 없다는 뜻이다(오류가 아니다). */
  readonly recordingUrl: string | null;
  readonly title: string;
  /** 딥링크 초기 재생 위치(초). 자리만 잡고 재생은 시작하지 않는다. */
  readonly initialSeconds?: number;
  readonly seekRequest?: SeekRequest | null;
  /** 재생 위치가 바뀔 때마다 초 단위로 알린다. 전사 하이라이트가 이 값을 따라간다. */
  readonly onTimeChange?: (seconds: number) => void;
  /**
   * URL 만료 대응. 미디어 요청의 401 은 상태코드가 JS 에 보이지 않고 video `error` 로만
   * 오므로, 오류가 나면 이 콜백으로 새 URL 을 받아 이어서 재생한다. `null` 이면 재발급 실패.
   */
  readonly reissueUrl?: () => Promise<string | null>;
}

const PLAYBACK_RATES = [1, 1.25, 1.5, 2] as const;

const rateLabel = (rate: number) => (Number.isInteger(rate) ? `${rate}.0x` : `${rate}x`);

/**
 * jsdom 에는 재생 구현이 없고, 브라우저에서도 사용자 제스처 없는 `play()` 는 거부될 수 있다.
 * 어느 쪽이든 재생 실패는 "일시정지 상태로 남는 것"이지 오류 화면이 아니다.
 */
const safePlay = (video: HTMLVideoElement) => {
  try {
    void video.play()?.catch?.(() => {});
  } catch {
    // jsdom: Not implemented — 테스트에서는 이벤트로 상태를 흉내 낸다.
  }
};

const DarkNotice = ({ icon, title, detail }: { icon: ReactNode; title: string; detail: string }) => (
  <div className="flex aspect-video flex-col items-center justify-center gap-2 bg-[linear-gradient(120deg,#1c2036,#20263f_55%,#1a1f34)] px-6 text-center">
    <div className="flex justify-center">{icon}</div>
    <div className="text-[15px] font-extrabold text-white">{title}</div>
    <div className="text-xs text-panel-dim">{detail}</div>
  </div>
);

/**
 * 공통 녹화 다시 보기 플레이어(REPORT-S-001).
 *
 * <p>재생 위치·재생 여부의 진실은 `<video>` 가 갖는다. React 상태는 컨트롤을 그리기 위한
 * 사본일 뿐이며 이벤트(`timeupdate`·`play`·`pause`)로만 따라간다 — 두 곳에서 같은 값을
 * 소유하면 스크럽 중에 서로를 덮어쓴다.
 *
 * <p>URL 만료 재발급은 **한 번만** 시도한다. 새 URL 마저 죽으면 만료가 아니라 파일·서버
 * 문제라서, 무한히 다시 받아 봐야 재생은 되지 않고 요청만 쌓인다.
 */
export function ReportPlayer({
  recordingUrl,
  title,
  initialSeconds = 0,
  seekRequest = null,
  onTimeChange,
  reissueUrl,
}: ReportPlayerProps) {
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const shellRef = useRef<HTMLDivElement | null>(null);

  // 재발급으로 src 가 바뀔 수 있어 URL 은 상태다. prop 은 초기값으로만 쓴다 — 부모가
  // `recordingUrl` 을 key 로 주므로 재조회로 URL 이 바뀌면 리마운트로 처음부터 다시 시작한다.
  const [url, setUrl] = useState(recordingUrl);
  const [playing, setPlaying] = useState(false);
  const [currentSeconds, setCurrentSeconds] = useState(0);
  const [durationSeconds, setDurationSeconds] = useState(0);
  const [rate, setRate] = useState<number>(PLAYBACK_RATES[0]);
  const [muted, setMuted] = useState(false);
  const [failed, setFailed] = useState(false);

  // 메타데이터가 오기 전에 도착한 이동 명령과, 재발급 후 복원할 위치를 담아 둔다.
  const pendingSeekRef = useRef<{ seconds: number; resume: boolean } | null>(
    initialSeconds > 0 ? { seconds: initialSeconds, resume: false } : null,
  );
  const reissuedRef = useRef(false);

  useEffect(() => {
    if (seekRequest === null) return;
    const video = videoRef.current;
    // HAVE_METADATA(1) 전에는 currentTime 을 설정해도 무시하는 브라우저가 있다.
    if (video !== null && video.readyState >= 1) {
      video.currentTime = seekRequest.seconds;
      safePlay(video);
    } else {
      pendingSeekRef.current = { seconds: seekRequest.seconds, resume: true };
    }
  }, [seekRequest]);

  const handleLoadedMetadata = () => {
    const video = videoRef.current;
    if (video === null) return;
    setDurationSeconds(Number.isFinite(video.duration) ? video.duration : 0);

    const pending = pendingSeekRef.current;
    if (pending !== null) {
      pendingSeekRef.current = null;
      video.currentTime = pending.seconds;
      if (pending.resume) safePlay(video);
    }
  };

  const handleTimeUpdate = () => {
    const video = videoRef.current;
    if (video === null) return;
    setCurrentSeconds(video.currentTime);
    onTimeChange?.(video.currentTime);
  };

  const handleError = () => {
    const video = videoRef.current;
    if (url === null) return;

    if (reissuedRef.current || reissueUrl === undefined) {
      setFailed(true);
      return;
    }

    // 만료로 보고 한 번만 재발급한다. 복원 위치는 죽기 직전 값이다.
    reissuedRef.current = true;
    const resumeAt = video?.currentTime ?? currentSeconds;
    const resume = playing;
    void reissueUrl().then((fresh) => {
      if (fresh === null) {
        setFailed(true);
        return;
      }
      pendingSeekRef.current = { seconds: resumeAt, resume };
      setFailed(false);
      setUrl(fresh);
    });
  };

  const togglePlay = () => {
    const video = videoRef.current;
    if (video === null) return;
    if (video.paused) {
      safePlay(video);
    } else {
      video.pause();
    }
  };

  const skipForward = () => {
    const video = videoRef.current;
    if (video === null) return;
    video.currentTime = Math.min(video.currentTime + 10, durationSeconds || video.currentTime + 10);
  };

  const scrubTo = (seconds: number) => {
    const video = videoRef.current;
    if (video === null) return;
    video.currentTime = seconds;
    setCurrentSeconds(seconds);
  };

  const cycleRate = () => {
    const next =
      PLAYBACK_RATES[(PLAYBACK_RATES.indexOf(rate as (typeof PLAYBACK_RATES)[number]) + 1) %
        PLAYBACK_RATES.length];
    setRate(next);
    const video = videoRef.current;
    if (video !== null) video.playbackRate = next;
  };

  const toggleMuted = () => {
    const next = !muted;
    setMuted(next);
    const video = videoRef.current;
    if (video !== null) video.muted = next;
  };

  const toggleFullscreen = () => {
    // jsdom 과 일부 브라우저(iOS Safari)에는 없다. 없으면 조용히 아무것도 하지 않는다.
    if (document.fullscreenElement !== null) {
      void document.exitFullscreen?.().catch(() => {});
      return;
    }
    void shellRef.current?.requestFullscreen?.().catch(() => {});
  };

  const body = () => {
    if (url === null) {
      return (
        <DarkNotice
          icon={<PictoClockMuted size={38} />}
          title="녹화가 아직 준비되지 않았어요"
          detail="분석이 끝나면 다시 보기가 열려요."
        />
      );
    }

    if (failed) {
      return (
        <DarkNotice
          icon={<PictoWarn size={38} />}
          title="녹화를 재생하지 못했어요"
          detail="네트워크를 확인하고 잠시 후 다시 시도해 주세요."
        />
      );
    }

    return (
      <>
        <div className="relative aspect-video bg-black">
          {/* 자막·컨트롤을 직접 그리지 않고 옆의 전사 패널이 자막 역할을 한다. */}
          <video
            ref={videoRef}
            src={url}
            preload="metadata"
            playsInline
            className="size-full object-contain"
            onClick={togglePlay}
            onLoadedMetadata={handleLoadedMetadata}
            onTimeUpdate={handleTimeUpdate}
            onPlay={() => setPlaying(true)}
            onPause={() => setPlaying(false)}
            onError={handleError}
            data-testid="report-video"
          />
          {!playing && (
            <button
              type="button"
              aria-label="재생"
              onClick={togglePlay}
              className="absolute inset-0 flex cursor-pointer items-center justify-center border-0 bg-transparent"
            >
              <span className="flex size-[66px] items-center justify-center rounded-full bg-white/15 backdrop-blur-[4px]">
                <span className="ml-[5px] text-[22px] text-white">▶</span>
              </span>
            </button>
          )}
          {!playing && (
            <div className="pointer-events-none absolute inset-x-[22px] bottom-[18px]">
              <div className="truncate text-base font-extrabold text-white [text-shadow:0_2px_8px_rgba(0,0,0,.4)]">
                {title}
              </div>
              <div className="mt-0.5 text-xs text-panel-dim">강의 다시보기</div>
            </div>
          )}
        </div>

        <input
          type="range"
          aria-label="재생 위치"
          min={0}
          max={durationSeconds > 0 ? durationSeconds : 0}
          step={1}
          value={Math.min(currentSeconds, durationSeconds > 0 ? durationSeconds : 0)}
          disabled={durationSeconds <= 0}
          onChange={(event) => scrubTo(Number(event.target.value))}
          className="h-1 w-full cursor-pointer appearance-auto bg-[#2f344f] accent-violet"
        />

        {/* 재생 컨트롤 글리프는 시안 그대로 둔다 — 픽토그램 카탈로그(258)에 재생·정지·볼륨·
            전체화면 아이콘이 없다. 스크린리더는 각 버튼의 aria-label 을 읽는다. */}
        <div className="flex items-center gap-4 px-4 py-3 text-[#c7ccf0]">
          <button
            type="button"
            aria-label={playing ? "일시정지" : "재생"}
            onClick={togglePlay}
            className="cursor-pointer border-0 bg-transparent text-[15px] text-inherit"
          >
            {playing ? "⏸" : "▶"}
          </button>
          <button
            type="button"
            aria-label="10초 앞으로"
            onClick={skipForward}
            className="cursor-pointer border-0 bg-transparent text-[15px] text-inherit"
          >
            ⏭
          </button>
          <button
            type="button"
            aria-label={muted ? "소리 켜기" : "소리 끄기"}
            onClick={toggleMuted}
            className="cursor-pointer border-0 bg-transparent text-sm text-inherit"
          >
            {muted ? "🔇" : "🔊"}
          </button>
          <span className="font-mono text-[12.5px] text-panel-dim">
            {formatOffset(currentSeconds)} / {formatOffset(durationSeconds)}
          </span>
          <span className="flex-1" />
          <button
            type="button"
            aria-label="재생 속도 바꾸기"
            onClick={cycleRate}
            className="cursor-pointer border-0 bg-transparent text-[12.5px] font-bold text-inherit"
          >
            {rateLabel(rate)}
          </button>
          <button
            type="button"
            aria-label="전체 화면"
            onClick={toggleFullscreen}
            className="cursor-pointer border-0 bg-transparent text-sm text-inherit"
          >
            ⛶
          </button>
        </div>
      </>
    );
  };

  return (
    <div
      ref={shellRef}
      className="flex flex-col overflow-hidden rounded-2xl bg-panel-video shadow-[0_8px_30px_rgba(20,25,50,.22)]"
    >
      {body()}
    </div>
  );
}
