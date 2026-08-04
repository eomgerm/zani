"use client";

import { ChevronDownIcon, PictoCamera, Select, type SelectOption } from "@/shared/ui";
import { MICROPHONE_LEVEL_THRESHOLD, type DeviceTestFailure } from "./deviceTest";
import { useDevicePreview, type DevicePreviewState } from "./useDevicePreview";

export type { DevicePreviewState } from "./useDevicePreview";

/**
 * 마이크 원인별 짧은 안내 문구. 마이크 입력 레벨 위 말풍선으로 노출한다(우선순위: 배열 순서).
 * 실패 상세 안내 박스는 두지 않는다(컬럼 높이가 늘어 페이지에 스크롤이 생겼음). 카메라 실패는
 * 미리보기 오버레이와 오른쪽 체크리스트로 이미 드러난다.
 */
const MICROPHONE_HINT_MESSAGES: Partial<Record<DeviceTestFailure, string>> = {
  MICROPHONE_PERMISSION_DENIED: "마이크 권한이 거부되어 있어요",
  MICROPHONE_NOT_SELECTED: "마이크가 연결되어 있지 않아요",
  MICROPHONE_DISABLED: "마이크가 꺼져 있어요",
};

/**
 * 입장 전 카메라·마이크 미리보기와 장치 선택, 대체 장치 안내 UI.
 *
 * 미디어 획득·판정 로직은 useDevicePreview 훅이, 렌더링은 이 컴포넌트가 담당한다.
 * 마이크 문제는 입력 레벨 위 말풍선으로만 간단히 알린다. 실패 상세 안내 박스는 두지 않는다
 * (미리보기 컬럼 높이가 늘어 페이지에 스크롤이 생겼음). 실패 여부는 오른쪽 체크리스트로도 표시한다.
 */
export function DevicePreview({
  onStateChange,
  levelSampleIntervalMs = 200,
}: {
  onStateChange?: (state: DevicePreviewState) => void;
  /** 마이크 입력 레벨 샘플 주기(ms). 테스트에서 짧게 조정할 수 있다. */
  levelSampleIntervalMs?: number;
}) {
  const {
    videoRef,
    cameras,
    microphones,
    selectedCameraId,
    selectedMicrophoneId,
    activeCameraId,
    activeMicrophoneId,
    cameraEnabled,
    microphoneEnabled,
    cameraHasVideoFrame,
    microphoneLevel,
    cameraRequesting,
    microphoneRequesting,
    result,
    cameraNotice,
    microphoneNotice,
    selectCamera,
    selectMicrophone,
    toggleCamera,
    toggleMicrophone,
  } = useDevicePreview({ onStateChange, levelSampleIntervalMs });

  const levelPercent = Math.min(100, Math.round(microphoneLevel * 100));
  const levelPassed = microphoneLevel >= MICROPHONE_LEVEL_THRESHOLD;

  // 마이크 원인별 말풍선 문구. 요청(전환·초기 획득) 진행 중에는 판정이 확정될 때까지 띄우지 않는다.
  const microphoneHint = microphoneRequesting
    ? null
    : (result.failures.map((failure) => MICROPHONE_HINT_MESSAGES[failure]).find(Boolean) ?? null);

  return (
    <div className="flex flex-col gap-4">
      {/* 카메라 미리보기 */}
      <div className="relative min-h-[420px] overflow-hidden rounded-[22px] bg-[#1a1d30]">
        <video
          ref={videoRef}
          data-testid="camera-preview"
          autoPlay
          muted
          playsInline
          className="absolute inset-0 size-full -scale-x-100 object-cover"
        />
        {!cameraHasVideoFrame && (
          <div className="absolute inset-0 flex flex-col items-center justify-center gap-3 px-[30px] text-center">
            <PictoCamera size={36} />
            <div className="text-lg font-extrabold text-white [text-shadow:0_2px_10px_#0008]">
              {cameraRequesting ? "카메라 권한을 확인하고 있어요" : "카메라 영상이 보이지 않아요"}
            </div>
            <div className="text-[13.5px] leading-[1.55] text-white/80">
              {cameraRequesting
                ? "브라우저가 권한을 요청하면 허용을 눌러 주세요"
                : "카메라 연결과 권한을 확인한 뒤 다시 시도해 주세요"}
            </div>
          </div>
        )}
        {cameraHasVideoFrame && !cameraEnabled && (
          <div className="absolute inset-0 flex flex-col items-center justify-center gap-3 bg-[#1a1d30] px-[30px] text-center">
            <div className="text-lg font-extrabold text-white">카메라가 꺼져 있어요</div>
            <div className="text-[13.5px] leading-[1.55] text-white/80">
              입장하려면 아래 버튼으로 카메라를 켜 주세요
            </div>
          </div>
        )}
        <div className="absolute top-[18px] left-[18px] z-stage-chip font-bold">
          카메라 미리보기
        </div>

        {/* 카메라·마이크 컨트롤: [아이콘+라벨 토글 | 장치 선택 화살표] 분할 알약 */}
        <div className="absolute bottom-[14px] left-1/2 flex -translate-x-1/2 gap-2.5">
          <DeviceControl
            toggleTestId="microphone-toggle"
            selectTestId="microphone-select"
            selectAriaLabel="마이크 선택"
            enabled={microphoneEnabled}
            onToggle={toggleMicrophone}
            Icon={MicIcon}
            labelWhenOn="음소거"
            labelWhenOff="음소거 해제"
            options={microphones}
            // 실제로 열린 장치를 우선 표시한다(선택 장치를 못 열어 폴백한 경우 선택값과 다를 수 있다).
            value={activeMicrophoneId ?? selectedMicrophoneId}
            onChange={selectMicrophone}
          />
          <DeviceControl
            toggleTestId="camera-toggle"
            selectTestId="camera-select"
            selectAriaLabel="카메라 선택"
            enabled={cameraEnabled}
            onToggle={toggleCamera}
            Icon={CameraIcon}
            labelWhenOn="비디오 중지"
            labelWhenOff="비디오 시작"
            options={cameras}
            // 실제로 열린 장치를 우선 표시한다(선택 장치를 못 열어 폴백한 경우 선택값과 다를 수 있다).
            value={activeCameraId ?? selectedCameraId}
            onChange={selectCamera}
          />
        </div>
      </div>

      {/* 마이크 입력 레벨 (장치 선택은 미리보기 하단 알약의 화살표 메뉴에서). */}
      {/* 마이크 문제는 레벨 위 말풍선으로만 알린다(절대 위치라 레이아웃 높이를 늘리지 않음). */}
      {/* 연결만 되어 있으면 입력이 무음이어도 안내하지 않는다. */}
      <div className="relative z-card-lg flex flex-col gap-2 px-[22px] py-[18px]">
        {microphoneHint && (
          <div
            data-testid="mic-hint-bubble"
            role="status"
            className="absolute left-[22px] top-0 z-10 flex -translate-y-[calc(100%+8px)] items-center rounded-[10px] bg-[#1a1d30] px-3 py-2 text-[12px] font-semibold text-white shadow-[0_6px_20px_rgba(0,0,0,.22)]"
          >
            {microphoneHint}
            {/* 아래를 향하는 말풍선 꼬리 */}
            <span
              aria-hidden
              className="absolute left-5 top-full size-2.5 -translate-y-1/2 rotate-45 bg-[#1a1d30]"
            />
          </div>
        )}
        <div className="flex items-center gap-[11px]">
          <span className="flex-none text-[13px] font-bold text-ink-faint">마이크 입력 레벨</span>
          <div
            data-testid="mic-level"
            data-level-passed={levelPassed}
            className="h-2.5 flex-1 overflow-hidden rounded-[5px] bg-faint"
          >
            <div
              className={`h-full rounded-[5px] transition-[width] duration-150 ${
                levelPassed ? "bg-primary" : "bg-line-soft"
              }`}
              style={{ width: `${levelPercent}%` }}
            />
          </div>
        </div>
      </div>

      {/* 선택 장치를 못 열어 다른 장치로 대체했을 때의 안내(드물게 발생). */}
      {(cameraNotice || microphoneNotice) && (
        <div className="flex flex-col gap-[9px] rounded-[20px] border border-line-mint bg-canvas px-5 py-[18px]">
          {cameraNotice && (
            <div
              data-testid="camera-fallback-notice"
              className="flex gap-2 text-[12.5px] leading-[1.6] text-ink-faint"
            >
              <span className="shrink-0 text-primary">!</span>
              {cameraNotice}
            </div>
          )}
          {microphoneNotice && (
            <div
              data-testid="microphone-fallback-notice"
              className="flex gap-2 text-[12.5px] leading-[1.6] text-ink-faint"
            >
              <span className="shrink-0 text-primary">!</span>
              {microphoneNotice}
            </div>
          )}
        </div>
      )}
    </div>
  );
}

/**
 * 미리보기 하단의 [아이콘·라벨 토글 | 장치 선택 화살표] 분할 알약.
 * 카메라·마이크가 동일한 구조라 하나의 컴포넌트로 공유한다.
 */
function DeviceControl({
  toggleTestId,
  selectTestId,
  selectAriaLabel,
  enabled,
  onToggle,
  Icon,
  labelWhenOn,
  labelWhenOff,
  options,
  value,
  onChange,
}: {
  toggleTestId: string;
  selectTestId: string;
  selectAriaLabel: string;
  enabled: boolean;
  onToggle: () => void;
  Icon: ({ off }: { off: boolean }) => React.ReactElement;
  labelWhenOn: string;
  labelWhenOff: string;
  options: readonly SelectOption[];
  value: string | null;
  onChange: (value: string) => void;
}) {
  return (
    <div className="flex items-center rounded-full border border-white/40 bg-[#0e1020cc] backdrop-blur-[6px]">
      <button
        type="button"
        data-testid={toggleTestId}
        aria-pressed={enabled}
        onClick={onToggle}
        className="flex cursor-pointer items-center gap-2 rounded-l-full border-0 bg-transparent py-2.5 pl-4 pr-2.5 text-[13px] font-extrabold text-white transition-colors hover:bg-white/10"
      >
        <Icon off={!enabled} />
        {enabled ? labelWhenOn : labelWhenOff}
      </button>
      <span aria-hidden className="h-[18px] w-px bg-white/25" />
      <Select
        data-testid={selectTestId}
        aria-label={selectAriaLabel}
        options={options}
        value={value}
        onChange={onChange}
        disabled={options.length === 0}
        placement="top"
        trigger={({ open }) => (
          <ChevronDownIcon
            className={`size-[15px] text-white transition-transform ${open ? "rotate-180" : ""}`}
          />
        )}
        triggerClassName="flex cursor-pointer items-center rounded-r-full border-0 bg-transparent py-[13px] pl-2 pr-3.5 transition-colors hover:bg-white/10 disabled:cursor-not-allowed disabled:opacity-50"
        listClassName="left-auto right-0 w-60"
      />
    </div>
  );
}

/** 마이크 아이콘. off 이면 붉은색 + 사선을 그린다. */
function MicIcon({ off }: { off: boolean }) {
  return (
    <svg
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden
      className={`size-[17px] shrink-0 ${off ? "text-danger-light" : "text-white"}`}
    >
      <path d="M12 2a3 3 0 0 1 3 3v6a3 3 0 0 1-6 0V5a3 3 0 0 1 3-3Z" />
      <path d="M19 10v1a7 7 0 0 1-14 0v-1" />
      <line x1="12" x2="12" y1="18" y2="21" />
      {off && <line x1="4" y1="3" x2="20" y2="21" />}
    </svg>
  );
}

/** 카메라 아이콘. off 이면 붉은색 + 사선을 그린다. */
function CameraIcon({ off }: { off: boolean }) {
  return (
    <svg
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden
      className={`size-[17px] shrink-0 ${off ? "text-danger-light" : "text-white"}`}
    >
      <path d="m16 10 6-4v12l-6-4" />
      <rect x="2" y="6" width="14" height="12" rx="2" />
      {off && <line x1="4" y1="3" x2="20" y2="21" />}
    </svg>
  );
}
