"use client";

import { use, useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";

import { useAuth } from "@/domains/auth/presentation/AuthProvider";
import { canonicalInviteCode } from "@/domains/lecture/domain/inviteCode";
import {
  JoinSessionRequestError,
  joinSession as joinSessionApi,
  type SessionJoiner,
} from "@/domains/lecture/infrastructure/joinSessionApi";
import {
  detectBrowserSupport,
  readBrowserEnvironment,
  type BrowserSupportFailure,
  type BrowserSupportResult,
} from "@/features/media/browserSupport";
import { DevicePreview, type DevicePreviewState } from "@/features/media/DevicePreview";
import { writePrejoinResult } from "@/features/media/prejoinResult";

/** 브라우저 실패 코드별 사용자 안내 문구. */
const BROWSER_FAILURE_MESSAGES: Record<BrowserSupportFailure, string> = {
  NOT_CHROME:
    "Chrome 브라우저에서만 수업에 입장할 수 있어요. Chrome 으로 다시 접속해 주세요.",
  MEDIA_DEVICES_UNSUPPORTED:
    "이 브라우저는 카메라·마이크 장치 접근을 지원하지 않아요. 최신 Chrome 으로 접속해 주세요.",
  GET_USER_MEDIA_UNSUPPORTED:
    "이 브라우저는 카메라·마이크 캡처를 지원하지 않아요. 최신 Chrome 으로 접속해 주세요.",
  PERMISSIONS_API_UNSUPPORTED:
    "이 브라우저는 권한 확인을 지원하지 않아요. 최신 Chrome 으로 접속해 주세요.",
};

/** 입장 실패 원인별 사용자 안내 문구. 서버가 돌려준 업무 코드를 우선 본다. */
const joinFailureMessage = (error: unknown, code: string): string => {
  if (error instanceof JoinSessionRequestError) {
    if (error.code === "SESSION_APP_007") {
      return "정원이 가득 찼어요. 강사에게 문의해 주세요.";
    }
    if (error.code === "SESSION_APP_008") {
      return "아직 시작하지 않았거나 이미 끝난 수업이에요. 강사가 수업을 시작하면 다시 시도해 주세요.";
    }
    if (error.code === "SESSION_APP_009") {
      return "강사가 아직 수업을 시작하지 않았어요. 시작한 뒤 다시 시도해 주세요.";
    }
    if (error.status === 404) {
      return "그런 초대 코드의 수업이 없어요. 코드를 다시 확인해 주세요.";
    }
    if (error.status === 400) {
      // 서버가 코드 모양을 거절한 경우다. 어떤 값을 보냈는지 같이 보여줘야 링크가 잘린 건지 코드가 바뀐 건지 사용자가 구분할 수 있다.
      return `초대 코드 형식이 올바르지 않아요. 영문·숫자 8자여야 합니다. (보낸 코드: ${code})`;
    }
    if (error.status === 401) {
      return "로그인이 필요해요. 다시 로그인한 뒤 시도해 주세요.";
    }
  }
  return "입장하지 못했어요. 잠시 후 다시 시도해 주세요.";
};

/**
 * SC-08 입장 전 점검. 브라우저(Chrome)·카메라·마이크를 검증하고, 모두 통과해야 입장 버튼을 활성화한다.
 *
 * <p>입장은 서버가 확정한다(POST /api/v1/sessions/join). 장치 점검을 통과했더라도 수업이 진행 중이 아니거나 정원이 찼으면 서버가 거절하며, 그때는 방으로 이동하지 않고
 * 재시도할 수 있는 오류를 보여준다.
 *
 * <p>이동 주소에는 **응답의 세션 ID** 를 쓴다. 초대 코드와 세션 ID 는 다른 값이라, 코드를 그대로 넣으면 강의실의 미디어 토큰 발급이 실패한다.
 *
 * <p>선택한 장치 ID 와 통과 시각은 강의실이 같은 장치로 붙도록 로컬에 남긴다. 서버 측 장치 검증(서명·만료)은 아직 백엔드가 없어 연동하지 않는다.
 */
export default function Page({
  params,
  joinSession = joinSessionApi,
}: {
  params: Promise<{ inviteCode: string }>;
  /** 테스트에서 API 경계를 대체하기 위한 주입점. */
  joinSession?: SessionJoiner;
}) {
  const { inviteCode } = use(params);
  const router = useRouter();
  const { accessToken } = useAuth();
  const [joining, setJoining] = useState(false);
  const [joinError, setJoinError] = useState<string | null>(null);

  // SSR 시점에는 navigator 가 없으므로 마운트 후 판정한다. null 은 판정 전 상태.
  const [browserSupport, setBrowserSupport] = useState<BrowserSupportResult | null>(null);
  const [deviceState, setDeviceState] = useState<DevicePreviewState | null>(null);
  const [testedAt, setTestedAt] = useState<string | null>(null);

  useEffect(() => {
    // SSR HTML 과의 hydration 불일치를 피하기 위해 마운트 후 한 번만 판정한다.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setBrowserSupport(detectBrowserSupport(readBrowserEnvironment()));
  }, []);

  // 장치 판정 보고를 받을 때 통과 시각도 함께 기록한다.
  // 통과가 깨지면 초기화하고 다시 통과할 때 새로 기록한다.
  const handleDeviceStateChange = useCallback((state: DevicePreviewState) => {
    setDeviceState(state);
    setTestedAt((prev) => (state.result.passed ? (prev ?? new Date().toISOString()) : null));
  }, []);

  const devicePassed = deviceState?.result.passed ?? false;

  const browserSupported = browserSupport?.supported ?? false;
  const canEnter = browserSupported && devicePassed && testedAt !== null;

  const handleEnter = useCallback(async () => {
    if (!canEnter || !deviceState || !testedAt || joining) {
      return;
    }
    if (accessToken === null) {
      setJoinError("로그인이 필요해요. 다시 로그인한 뒤 시도해 주세요.");
      return;
    }

    setJoining(true);
    setJoinError(null);
    try {
      // 서버가 상태·정원·초대 코드를 검증하고 세션 ID 를 확정한다. 실패하면 방으로 넘어가지 않는다.
      const joined = await joinSession(inviteCode, accessToken);
      // 강의실이 같은 장치로 붙도록 선택 결과를 남긴다. 장치 원본 데이터는 저장하지 않는다.
      writePrejoinResult(inviteCode, {
        cameraDeviceId: deviceState.cameraDeviceId,
        microphoneDeviceId: deviceState.microphoneDeviceId,
        testedAt,
      });
      router.push(`/room/${joined.sessionId}`);
    } catch (caught) {
      setJoinError(joinFailureMessage(caught, canonicalInviteCode(inviteCode)));
      setJoining(false);
    }
  }, [canEnter, deviceState, testedAt, joining, accessToken, joinSession, inviteCode, router]);

  const deviceFailures = deviceState?.result.failures ?? [];
  const cameraOk =
    devicePassed || (deviceState !== null && !deviceFailures.some((f) => f.startsWith("CAMERA")));
  const microphoneOk =
    devicePassed ||
    (deviceState !== null && !deviceFailures.some((f) => f.startsWith("MICROPHONE")));

  return (
    <div className="flex min-h-screen items-center justify-center bg-mint p-7">
      <div className="w-full max-w-[1200px]">
        <div className="mb-5 flex items-center gap-3">
          <Link
            href="/home"
            className="z-btn size-11 rounded-[13px] border border-line-muted bg-surface text-[17px] text-ink-sub shadow-[0_2px_8px_rgba(24,74,62,.05)]"
          >
            ←
          </Link>
          <div>
            <div className="text-[22px] font-extrabold tracking-[-.4px]">입장 전 점검</div>
            <div className="mt-0.5 text-[13.5px] text-ink-faint">
              초대 코드 <b className="text-primary">{inviteCode}</b> · 카메라와 마이크를 확인해
              주세요
            </div>
          </div>
        </div>

        <div className="grid grid-cols-[1.55fr_1fr] items-start gap-[22px] max-lg:grid-cols-1">
          {/* 좌: 미리보기·장치 선택·오류 복구 (브라우저가 장치 접근을 지원할 때만) */}
          {browserSupport === null ? (
            <div className="flex min-h-[420px] items-center justify-center rounded-[22px] bg-[#1a1d30] text-sm font-bold text-white/80">
              브라우저 환경을 확인하고 있어요…
            </div>
          ) : browserSupport.failures.includes("GET_USER_MEDIA_UNSUPPORTED") ? (
            <div
              data-testid="browser-unsupported-panel"
              className="flex min-h-[420px] flex-col items-center justify-center gap-3 rounded-[22px] bg-[#1a1d30] px-[30px] text-center"
            >
              <span className="text-4xl">🚫</span>
              <div className="text-lg font-extrabold text-white">
                이 브라우저에서는 장치 테스트를 할 수 없어요
              </div>
              <div className="text-[13.5px] leading-[1.55] text-white/80">
                최신 Chrome 브라우저로 다시 접속해 주세요
              </div>
            </div>
          ) : (
            <DevicePreview onStateChange={handleDeviceStateChange} />
          )}

          {/* 우: 점검 결과·안내·입장 */}
          <div className="flex flex-col gap-4">
            <div className="z-card-lg px-[22px] py-5">
              <div className="mb-[15px] text-base font-extrabold">장치 확인</div>
              <div className="flex flex-col gap-[13px]">
                <ChecklistItem
                  testId="checklist-browser"
                  ok={browserSupported}
                  pending={browserSupport === null}
                >
                  브라우저 · Chrome
                </ChecklistItem>
                <ChecklistItem
                  testId="checklist-camera"
                  ok={cameraOk}
                  pending={deviceState === null}
                >
                  카메라 영상
                </ChecklistItem>
                <ChecklistItem
                  testId="checklist-microphone"
                  ok={microphoneOk}
                  pending={deviceState === null}
                >
                  마이크 입력 레벨
                </ChecklistItem>
              </div>
            </div>

            {/* 브라우저 원인별 안내 */}
            {browserSupport !== null && browserSupport.failures.length > 0 && (
              <div className="flex flex-col gap-[9px] rounded-[20px] border border-line-mint bg-canvas px-5 py-[18px]">
                {browserSupport.failures.map((failure) => (
                  <div
                    key={failure}
                    data-testid={`browser-failure-${failure}`}
                    className="flex gap-2 text-[12.5px] leading-[1.6] text-ink-faint"
                  >
                    <span className="shrink-0 text-primary">!</span>
                    {BROWSER_FAILURE_MESSAGES[failure]}
                  </div>
                ))}
              </div>
            )}

            <div className="flex gap-[13px] rounded-[20px] border border-line-mint bg-canvas px-5 py-[18px]">
              <span className="shrink-0 text-xl text-primary">⧉</span>
              <div>
                <div className="mb-[5px] text-[13.5px] font-extrabold">이렇게 활용돼요</div>
                <p className="text-[12.5px] leading-[1.6] text-ink-faint">
                  카메라는 수업 중 표정, 시선, 고개 움직임 등을 분석해 이해도와 참여도를 파악하는 데
                  사용돼요. 분석 결과는 <span className="font-bold text-primary">본인에게만</span>{" "}
                  제공되며 안전하게 보호됩니다.
                </p>
              </div>
            </div>

            {joinError !== null && (
              <div
                role="alert"
                data-testid="prejoin-join-error"
                className="flex gap-2 rounded-[20px] border border-line-mint bg-canvas px-5 py-[18px] text-[12.5px] font-semibold leading-[1.6] text-danger"
              >
                <span className="shrink-0">!</span>
                {joinError}
              </div>
            )}

            <button
              type="button"
              data-testid="prejoin-enter-button"
              disabled={!canEnter || joining}
              onClick={handleEnter}
              className="z-btn z-btn-primary z-btn-block disabled:cursor-not-allowed disabled:opacity-40"
            >
              {joining ? "입장하고 있어요…" : "수업 입장하기"}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

/** 장치 확인 체크리스트 한 줄. 통과 여부에 따라 ✓/! 아이콘을 바꾼다. */
function ChecklistItem({
  ok,
  pending,
  testId,
  children,
}: {
  ok: boolean;
  pending: boolean;
  /** 상태를 관찰하기 위한 testid. data-ok 로 통과 여부를, data-pending 으로 판정 전 여부를 노출한다. */
  testId?: string;
  children: React.ReactNode;
}) {
  return (
    <div
      data-testid={testId}
      data-ok={ok}
      data-pending={pending}
      className="flex items-center gap-[11px]"
    >
      {pending ? (
        <span className="z-check bg-faint text-ink-faint">…</span>
      ) : ok ? (
        <span className="z-check">✓</span>
      ) : (
        <span className="z-check bg-[#ffe9e3] text-[#d9534f]">!</span>
      )}
      <span className="flex-1 text-sm font-semibold">{children}</span>
    </div>
  );
}
