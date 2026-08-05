"use client";

import { useMemo, useState } from "react";
import { ParticipantGrid } from "@/domains/lecture/presentation/components/room/ParticipantGrid";
import type { ParticipantTileData } from "@/domains/lecture/presentation/components/room/ParticipantTile";

/**
 * 참가자 그리드 배치 확인용 목업 화면(`/dev/grid`).
 *
 * <p>LiveKit 없이 동작한다 — 세션도 방도 만들지 않고, `videoRefFor` 를 주지 않아 타일이 아바타만
 * 그린다. 배치기가 인원수와 컨테이너 크기에 어떻게 반응하는지 눈으로 보기 위한 화면이라, 인원과
 * 스테이지 크기, 사이드 패널 여닫기를 직접 바꿔 볼 수 있게 해 둔다.
 *
 * <p>제품 화면이 아니다. 배치 검토가 끝나면 지워도 되는 파일이다(S15P11A105-274).
 */

const NAMES = [
  "박서준", "김민지", "이도현", "최유나", "정하늘", "한지우",
  "오세훈", "윤아름", "장민석", "서지호", "임채원", "고은비",
  "배준혁", "신다은", "권태윤", "홍서영", "문재원", "조하람",
];

const COLORS = [
  "#10b981", "#4a6fd6", "#f26d7d", "#f4c325", "#9b6dd6", "#2aa584",
  "#e0616f", "#4a7bd6", "#d68a4a", "#6dd6c1", "#d64a9b", "#7d9b4a",
];

/** 강의실에서 내 identity 자리. 이 사람만 음소거 버튼이 안 붙는다(자기 자신은 대상이 아니다). */
const ME = "mock-0";

/** 실제 강의실 사이드 패널 폭(340px) + 스테이지와의 간격(14px). */
const PANEL_SPACE = 354;

/** 실제 강의실에서 이 크기로 스테이지가 잡히는 상황들. 클릭 한 번으로 그 상태를 재현한다. */
const PRESETS: { label: string; width: number; height: number }[] = [
  { label: "노트북 1280", width: 1252, height: 628 },
  { label: "와이드 1600", width: 1572, height: 760 },
  { label: "넓고 낮음", width: 1400, height: 420 },
  { label: "좁고 높음", width: 620, height: 900 },
];

export default function GridMockupPage() {
  const [count, setCount] = useState(5);
  const [size, setSize] = useState(PRESETS[0] as { width: number; height: number });
  const [panelOpen, setPanelOpen] = useState(false);
  /** 강제 음소거된 참가자. 버튼이 실제로 뭔가를 해야 기능이 살아 있는지 확인된다. */
  const [muted, setMuted] = useState<string[]>([]);
  // 프리셋을 다시 누르면 직접 늘려 둔 크기를 되돌려야 하므로, 크기가 바뀔 때마다 상자를 새로 만든다.
  const [resetKey, setResetKey] = useState(0);

  const applyPreset = (preset: { width: number; height: number }) => {
    setSize(preset);
    setResetKey((key) => key + 1);
  };

  /** 목업 참가자. 카메라·마이크·손들기를 섞어 타일 배지가 실제로 보이게 한다. */
  const participants = useMemo<ParticipantTileData[]>(
    () =>
      Array.from({ length: count }, (_, i) => ({
        id: `mock-${i}`,
        name: NAMES[i % NAMES.length] ?? `참가자 ${i + 1}`,
        color: COLORS[i % COLORS.length] as string,
        role: i === 0 ? ("instructor" as const) : ("student" as const),
        cameraEnabled: i % 4 !== 3,
        microphoneEnabled: i % 3 !== 2 && !muted.includes(`mock-${i}`),
        handRaised: i % 7 === 5,
        speaking: i === 1,
      })),
    [count, muted],
  );

  return (
    <main className="min-h-screen bg-stage p-8 text-panel-text">
      <h1 className="mb-1 text-xl font-black text-primary">참가자 그리드 목업</h1>
      <p className="mb-6 text-[13px] text-panel-muted">
        LiveKit 없이 배치만 확인하는 화면입니다. 상자 오른쪽 아래 모서리를 끌면 크기가 바뀝니다.
      </p>

      <div className="mb-4 flex flex-wrap items-center gap-4">
        <label className="flex items-center gap-3 text-[13px] font-bold">
          참가자
          <input
            type="range"
            min={1}
            max={18}
            value={count}
            onChange={(event) => setCount(Number(event.target.value))}
            className="w-56 accent-[#10b981]"
          />
          <span className="w-16 font-mono text-sm text-primary">{count}명</span>
        </label>

        <div className="flex flex-wrap gap-2">
          {PRESETS.map((preset) => (
            <button
              key={preset.label}
              type="button"
              onClick={() => applyPreset(preset)}
              className="cursor-pointer rounded-lg border border-room-line bg-panel px-3 py-1.5 text-[12.5px] font-bold text-panel-soft hover:bg-room-control"
            >
              {preset.label}
            </button>
          ))}
        </div>

        {/* 참가자·채팅 패널을 여닫으면 스테이지 폭이 340px 줄어 배치가 재구성된다. 그때 타일이
            출렁이는지 보려고 둔 버튼이다 — 실제 강의실에서 가장 자주 일어나는 크기 변화다. */}
        <button
          type="button"
          onClick={() => setPanelOpen((open) => !open)}
          aria-pressed={panelOpen}
          className={`cursor-pointer rounded-lg border px-3 py-1.5 text-[12.5px] font-bold ${
            panelOpen
              ? "border-primary bg-[#0e2a20] text-[#2fbf88]"
              : "border-room-line bg-panel text-panel-soft hover:bg-room-control"
          }`}
        >
          사이드 패널 {panelOpen ? "닫기" : "열기"}
        </button>
      </div>

      <p className="mb-2 font-mono text-[12px] text-panel-muted">
        12명을 넘으면 페이지로 나뉩니다 · 학생 타일 오른쪽 위 음소거 버튼이 실제로 동작합니다
        {muted.length > 0 && ` · 음소거됨 ${muted.length}명`}
      </p>

      <div className="flex items-start gap-3.5">
        {/*
          `resize` 는 브라우저가 그냥 해 준다. 드래그 핸들을 직접 만들 이유가 없다.
          상자 크기가 바뀌면 그리드 안의 ResizeObserver 가 그대로 받아 배치를 다시 잡는다.
        */}
        <div
          key={resetKey}
          style={{ width: panelOpen ? size.width - PANEL_SPACE : size.width, height: size.height }}
          className="relative max-w-full overflow-hidden rounded-[18px] border border-room-line bg-stage [resize:both]"
        >
          <ParticipantGrid
            participants={participants}
            currentParticipantId={ME}
            isInstructor
            onMute={(id) => setMuted((prev) => (prev.includes(id) ? prev : [...prev, id]))}
          />
        </div>

        {/* 실제 사이드 패널 자리. 내용은 없고 폭만 차지한다 — 여기서 볼 것은 스테이지 쪽 반응이다. */}
        {panelOpen && (
          <div
            style={{ height: size.height }}
            className="flex w-[340px] shrink-0 items-center justify-center rounded-[18px] border border-white/5 bg-panel text-[13px] text-panel-muted"
          >
            사이드 패널 (340px)
          </div>
        )}
      </div>
    </main>
  );
}
