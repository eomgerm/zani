"use client";

import { useEffect, useMemo, useState } from "react";
import { createPortal } from "react-dom";
import { ChatIcon, PeopleIcon } from "@/shared/ui";
import { ParticipantGrid } from "./components/room/ParticipantGrid";
import type { ParticipantTileData } from "./components/room/ParticipantTile";
import { RoomControlBar } from "./components/room/RoomControlBar";
import { PipStage } from "./components/room/PipStage";
import { InviteStudentsPrompt } from "./components/room/InviteStudentsPrompt";
import { ScreenShareStage } from "./components/room/ScreenShareStage";
import { SpeakerStage } from "./components/room/SpeakerStage";
import { RoomSidePanel } from "./components/room/RoomSidePanel";
import { useDocumentPictureInPicture } from "./useDocumentPictureInPicture";
import { useLastSpeaker } from "./useLastSpeaker";

/**
 * 강의실 배치 확인용 목업 화면. `app/dev/room` 라우트가 로컬에서만 이것을 그린다.
 *
 * <p>LiveKit 없이 동작한다 — 방도 세션도 만들지 않고 `videoRefFor` 를 주지 않아 타일이 아바타만
 * 그린다. 그 외의 껍데기는 실제 강의실과 같은 컴포넌트다: 상단 바·갤러리·컨트롤 바·사이드 패널·
 * 화면 공유 오버레이·PiP 모두 `RoomScreen` 이 쓰는 것을 그대로 가져와 배치가 실제와 같게 보인다.
 *
 * <p>바꿀 수 있는 것은 인원수와 상태(공유 중·패널 열림·PiP)뿐이다. 제품 화면이 아니며 배치 검토가
 * 끝나면 지워도 되는 파일이다(S15P11A105-322).
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

/** 내 identity 자리. 이 사람만 음소거 버튼이 붙지 않는다(자기 자신은 대상이 아니다). */
const ME = "mock-0";

/** 상단 바 토글. RoomScreen 의 PanelToggle 과 같은 모양이다. */
function PanelToggle({
  active,
  label,
  onClick,
  children,
}: {
  active: boolean;
  label: string;
  onClick: () => void;
  children: React.ReactNode;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      title={label}
      aria-label={label}
      aria-pressed={active}
      className={`relative inline-flex size-11 shrink-0 cursor-pointer items-center justify-center rounded-[11px] border font-sans ${
        active
          ? "border-primary bg-[#0e2a20] text-[#2fbf88]"
          : "border-room-line bg-panel text-panel-soft"
      }`}
    >
      {children}
    </button>
  );
}

export function RoomMockupScreen() {
  const [count, setCount] = useState(5);
  const [panelOpen, setPanelOpen] = useState(false);
  const [panel, setPanel] = useState<"people" | "chat">("people");
  const [sharing, setSharing] = useState(false);
  const [view, setView] = useState<"gallery" | "speaker">("gallery");
  const [muted, setMuted] = useState<string[]>([]);
  const [me, setMe] = useState({ mic: true, cam: true, hand: false });
  const [reactMenuOpen, setReactMenuOpen] = useState(false);
  const [inviteDismissed, setInviteDismissed] = useState(false);

  const pip = useDocumentPictureInPicture();

  // 공유를 멈추면 PiP 창도 닫는다. 실제 강의실과 같은 규칙이다.
  useEffect(() => {
    if (!sharing) pip.close();
  }, [sharing, pip]);

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

  // 보기를 전환해도 직전 발화자를 잊지 않도록, 전환하는 이 자리에서 붙든다(실제 강의실과 같다).
  const lastSpeakerId = useLastSpeaker(participants);

  /** 사이드 패널은 다른 모양의 참가자 타입을 쓴다. 같은 목업 인원에서 만든다. */
  const panelParticipants = participants.map((p) => ({
    id: p.id,
    name: p.name,
    color: p.color,
    host: p.role === "instructor",
    cam: p.cameraEnabled,
    mic: p.microphoneEnabled,
    hand: p.handRaised,
  }));

  const mute = (id: string) => setMuted((prev) => (prev.includes(id) ? prev : [...prev, id]));
  const togglePanel = (next: "people" | "chat") => {
    setPanelOpen((open) => !(open && panel === next));
    setPanel(next);
  };

  return (
    <div className="relative flex h-screen flex-col bg-stage text-panel-text">
      {/* 조절판 — 목업에만 있는 부분이다. 아래는 전부 실제 강의실 컴포넌트다. */}
      <div className="flex shrink-0 flex-wrap items-center gap-4 border-b border-room-line bg-[#0b0d18] px-6 py-2.5 text-[12.5px]">
        <span className="font-extrabold text-primary">목업 조절판</span>
        <label className="flex items-center gap-2 font-bold">
          참가자
          <input
            type="range"
            min={1}
            max={18}
            value={count}
            onChange={(event) => setCount(Number(event.target.value))}
            className="w-48 accent-[#10b981]"
          />
          <span className="w-12 font-mono text-primary">{count}명</span>
        </label>
        <button
          type="button"
          onClick={() => setSharing((v) => !v)}
          aria-pressed={sharing}
          className={`cursor-pointer rounded-lg border px-3 py-1.5 font-bold ${
            sharing
              ? "border-primary bg-[#0e2a20] text-[#2fbf88]"
              : "border-room-line bg-panel text-panel-soft"
          }`}
        >
          화면 공유 {sharing ? "중지" : "시작"}
        </button>
        {sharing && pip.supported && (
          <button
            type="button"
            onClick={() => (pip.pipWindow ? pip.close() : void pip.open())}
            className="cursor-pointer rounded-lg border border-room-line bg-panel px-3 py-1.5 font-bold text-panel-soft"
          >
            PiP 창 {pip.pipWindow ? "닫기" : "열기"}
          </button>
        )}
        <span className="text-panel-muted">
          창 크기를 바꾸거나 패널을 여닫으면 배치가 다시 잡힌다
        </span>
      </div>

      {/* 초대 안내(강사). 실제 화면에서는 역할이 확정된 강사에게만 뜬다. */}
      {!inviteDismissed && (
        <InviteStudentsPrompt
          inviteUrl="http://localhost:3000/prejoin/ABCD-1234"
          onClose={() => setInviteDismissed(true)}
        />
      )}
      {/* 상단 바 — RoomScreen 과 같은 구성 */}
      <div className="flex shrink-0 items-center gap-4 px-6 py-[13px]">
        <div className="shrink-0 text-xl font-black tracking-[-.5px] text-primary">ZANI</div>
        <div className="min-w-0 truncate text-[14.5px] font-extrabold">React 상태관리 심화</div>
        <div className="flex-1" />
        <button
          type="button"
          onClick={() => setView(view === "gallery" ? "speaker" : "gallery")}
          className="inline-flex shrink-0 cursor-pointer items-center gap-1.5 whitespace-nowrap rounded-[11px] border border-[#262b42] bg-[#151830] px-4 py-[9px] font-sans text-[13.5px] font-extrabold text-panel-soft transition-colors hover:bg-room-control"
        >
          ⊞ {view === "gallery" ? "발표자 보기" : "전체 보기"}
        </button>
        <PanelToggle
          active={panelOpen && panel === "people"}
          label="참여자"
          onClick={() => togglePanel("people")}
        >
          <PeopleIcon />
        </PanelToggle>
        <PanelToggle
          active={panelOpen && panel === "chat"}
          label="채팅 열기"
          onClick={() => togglePanel("chat")}
        >
          <ChatIcon />
        </PanelToggle>
      </div>

      {/* 본문 */}
      <div className="flex min-h-0 flex-1 gap-3.5 p-3.5">
        <div className="flex min-w-0 flex-1 flex-col gap-3.5">
          <div className="relative min-h-0 flex-1 overflow-hidden rounded-[18px] bg-stage">
            {sharing ? (
              <div className="absolute inset-0 z-[6] flex flex-col bg-stage">
                {/* PiP 창이 열리면 공유 화면째로 그쪽으로 옮긴다 — 실제 강의실과 같은 규칙 */}
                {pip.pipWindow === null ? (
                  view === "speaker" ? (
                    /* 공유 중에도 보기 전환은 살아 있다. 발표자 보기에서는 옆줄 없이 자료만 크게 본다. */
                    <SpeakerStage
                      participants={participants}
                      attachScreen={null}
                      sharerLabel="내 화면"
                      localParticipantId={ME}
                      lastSpeakerId={lastSpeakerId}
                    />
                  ) : (
                    <ScreenShareStage
                      participants={participants}
                      attachScreen={null}
                      sharerLabel="내 화면"
                      localParticipantId={ME}
                    />
                  )
                ) : (
                  <div className="flex flex-1 items-center justify-center text-[13px] text-panel-muted">
                    PiP 창에서 보는 중입니다
                  </div>
                )}
              </div>
            ) : view === "gallery" ? (
              <ParticipantGrid
                participants={participants}
                currentParticipantId={ME}
                isInstructor
                videoRefFor={undefined}
                onMute={mute}
              />
            ) : (
              <SpeakerStage
                participants={participants}
                localParticipantId={ME}
                lastSpeakerId={lastSpeakerId}
              />
            )}
          </div>

          <RoomControlBar
            me={me}
            sharing={sharing}
            shareBlocked={false}
            reactMenuOpen={reactMenuOpen}
            mediaDisabled={false}
            microphoneBlocked={false}
            cameraBlocked={false}
            microphones={[]}
            cameras={[]}
            activeMicrophoneId={null}
            activeCameraId={null}
            onSelectMicrophone={() => {}}
            onSelectCamera={() => {}}
            onToggleMic={() => setMe((v) => ({ ...v, mic: !v.mic }))}
            onToggleCam={() => setMe((v) => ({ ...v, cam: !v.cam }))}
            onToggleShare={() => setSharing((v) => !v)}
            handDisabled={false}
            reactionDisabled={false}
            onToggleHand={() => setMe((v) => ({ ...v, hand: !v.hand }))}
            onToggleReactMenu={() => setReactMenuOpen((v) => !v)}
            onReact={() => setReactMenuOpen(false)}
            onLeave={() => {}}
          />
        </div>

        {panelOpen && (
          <RoomSidePanel
            panel={panel}
            participants={panelParticipants}
            messages={[]}
            meId={ME}
            isInstructor
            canSendChat
            onSendChat={() => {}}
            onRetryChat={() => {}}
            onMute={mute}
          />
        )}
      </div>

      {/* PiP 창(Document PiP). 실제 강의실과 같이 배치기로 그린다. */}
      {pip.pipWindow &&
        createPortal(
          <div className="relative flex h-screen flex-col bg-stage">
            <div className="min-h-0 flex-1 p-2">
              <PipStage
                participants={participants}
                {...(sharing ? { attachScreen: null } : {})}
                localParticipantId={ME}
              />
            </div>
            <div className="flex shrink-0 items-center justify-center gap-2 border-t border-room-line bg-[#0e1020] p-2 text-[12px] text-panel-muted">
              PiP 창 컨트롤 자리
            </div>
          </div>,
          pip.pipWindow.document.body,
        )}
    </div>
  );
}
