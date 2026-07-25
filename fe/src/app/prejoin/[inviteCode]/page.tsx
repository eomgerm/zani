"use client";

import { use, useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";

import {
  detectBrowserSupport,
  readBrowserEnvironment,
  type BrowserSupportFailure,
  type BrowserSupportResult,
} from "@/features/media/browserSupport";
import { DevicePreview, type DevicePreviewState } from "@/features/media/DevicePreview";

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

/** 입장 요청에 포함할 prejoin 테스트 통과 정보의 sessionStorage 키. */
function prejoinStorageKey(inviteCode: string): string {
  return `zani:prejoin:${inviteCode}`;
}

/**
 * SC-08 입장 전 점검. 브라우저(Chrome)·카메라·마이크를 검증하고,
 * 모두 통과해야 입장 버튼을 활성화한다.
 *
 * 통과 시 선택 장치 ID 와 테스트 통과 시각을 입장 요청 정보로 저장한다.
 * (POST /api/v1/sessions/{sessionId}/prejoin 연동 시 이 값을 요청 본문으로 보낸다.)
 */
export default function Page({ params }: { params: Promise<{ inviteCode: string }> }) {
  const { inviteCode } = use(params);
  const router = useRouter();

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

  const handleEnter = useCallback(() => {
    if (!canEnter || !deviceState || !testedAt) {
      return;
    }
    // 입장 요청(prejoin)에 포함할 값. 장치 원본 데이터는 저장하지 않는다.
    sessionStorage.setItem(
      prejoinStorageKey(inviteCode),
      JSON.stringify({
        cameraDeviceId: deviceState.cameraDeviceId,
        microphoneDeviceId: deviceState.microphoneDeviceId,
        testedAt,
      }),
    );
    router.push(`/room/${inviteCode}`);
  }, [canEnter, deviceState, testedAt, inviteCode, router]);

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

            <button
              type="button"
              data-testid="prejoin-enter-button"
              disabled={!canEnter}
              onClick={handleEnter}
              className="z-btn z-btn-primary z-btn-block disabled:cursor-not-allowed disabled:opacity-40"
            >
              수업 입장하기
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
