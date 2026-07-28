"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { ChatIcon, MonitorIcon, PeopleIcon } from "@/shared/ui";
import {
  participantTiles,
  participants as participantsFixture,
  publicMessages,
} from "./fixtures";
import { ParticipantGrid } from "./components/room/ParticipantGrid";
import { useRoomParticipants } from "./useRoomParticipants";
import { RoomControlBar } from "./components/room/RoomControlBar";
import { RoomSidePanel } from "./components/room/RoomSidePanel";
import { RoomProvider, useRoomConnection } from "./RoomProvider";
import { SessionTimeWarning } from "./components/room/SessionTimeWarning";
import { EndSessionButton } from "./components/room/EndSessionButton";
import { SessionPresenceNotice } from "./components/room/SessionPresenceNotice";
import { useRoomMediaControls } from "./useRoomMediaControls";
import { useSessionPresence } from "./useSessionPresence";

/**
 * SC-09 실시간 강의실 (어두운 테마). LiveKit room connection is attached here;
 * media track publishing remains out of scope.
 */
type RoomScreenProps = {
  sessionId: string;
  roomTitle?: string;
  /**
   * 종료 예정 시각(ISO-8601) 강제 지정. 평소에는 미디어 토큰 응답이 준 값을 쓰므로 넘길 필요가 없고,
   * 스토리북·테스트처럼 서버 없이 배너를 보여줄 때만 지정한다.
   */
  expiresAt?: string;
  /**
   * 입장 전 점검이 장치를 저장할 때 쓴 초대 코드. 지금은 강의실 경로 파라미터가 초대 코드와 같아
   * 기본값이 sessionId 지만, sessions/join 이 붙어 경로가 실제 세션 ID 로 바뀌면 이 값을 따로 넘겨야 한다.
   */
  prejoinInviteCode?: string;
};

type FloatingReaction = { key: number; emoji: string; left: number };

/** 상단 바의 참여자/채팅 토글 버튼 */
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
      className={`inline-flex size-11 cursor-pointer items-center justify-center rounded-[11px] border font-sans ${
        active
          ? "border-primary bg-[#0e2a20] text-[#2fbf88]"
          : "border-room-line bg-panel text-panel-soft"
      }`}
    >
      {children}
    </button>
  );
}

export function RoomScreen({ sessionId, roomTitle, expiresAt, prejoinInviteCode }: RoomScreenProps) {
  return (
    <RoomProvider sessionId={sessionId}>
      <RoomScreenContent
        sessionId={sessionId}
        roomTitle={roomTitle}
        expiresAt={expiresAt}
        prejoinInviteCode={prejoinInviteCode ?? sessionId}
      />
    </RoomProvider>
  );
}

function RoomScreenContent({
  sessionId,
  roomTitle = "React 상태관리 심화",
  expiresAt,
  prejoinInviteCode,
}: RoomScreenProps) {
  const router = useRouter();
  // 종료 예정 시각은 강의실 진입 시 미디어 토큰 응답으로 받는다. prop 은 테스트·스토리북 강제 지정용이다.
  const { sessionExpiresAt } = useRoomConnection();
  const media = useRoomMediaControls(prejoinInviteCode);
  // 서버는 이 heartbeat 로 강사 5분 유예·자동 종료를 판단한다(가이드 §12).
  const presence = useSessionPresence(sessionId);
  const { participants: tileParticipants, localParticipantId } = useRoomParticipants();
  const [view, setView] = useState<"gallery" | "speaker">("gallery");
  const [panel, setPanel] = useState<"people" | "chat">("people");
  const [panelOpen, setPanelOpen] = useState(false);
  const [handRaised, setHandRaised] = useState(false);
  // 마이크·카메라는 로컬 state 가 아니라 실제 publish 상태를 쓴다. 손들기는 아직 fixture(WebSocket 소관).
  const me = { mic: media.microphoneEnabled, cam: media.cameraEnabled, hand: handRaised };
  const [reactMenuOpen, setReactMenuOpen] = useState(false);
  const [sharing, setSharing] = useState(false);
  const [promptOpen, setPromptOpen] = useState(false);
  const [promptToast, setPromptToast] = useState<string | null>(null);
  const [alertOpen, setAlertOpen] = useState(false);
  const [reactions, setReactions] = useState<FloatingReaction[]>([]);
  const reactionSeq = useRef(0);
  const timers = useRef<ReturnType<typeof setTimeout>[]>([]);

  // 언마운트 시 남아 있는 애니메이션/토스트 타이머를 모두 정리한다.
  useEffect(
    () => () => {
      timers.current.forEach(clearTimeout);
    },
    [],
  );

  const track = useCallback((id: ReturnType<typeof setTimeout>) => {
    timers.current.push(id);
  }, []);

  // 역할은 백엔드가 토큰에 심은 값(useRoomParticipants)에서 파생한다. 프론트가 정하지 않는다.
  // 아직 room 이 붙지 않은 시연 상태에서는 강사 화면을 기준으로 본다.
  const connected = tileParticipants.length > 0;
  const isInstructor =
    !connected ||
    tileParticipants.find((p) => p.id === localParticipantId)?.role === "instructor";

  // room 에 참가자가 없으면 갤러리가 빈 화면이 되므로 사이드 패널과 같은 시연용 픽스처로 채운다.
  // 실제 참가자가 한 명이라도 잡히면 그쪽이 우선한다(WebSocket·미디어 연동 시 이 분기를 제거).
  const galleryParticipants = connected ? tileParticipants : participantTiles;

  // 사이드 패널 people/chat 목록은 아직 fixture 기반(WebSocket·57 소관).
  const meId = isInstructor ? "p0" : "p7";
  const list = participantsFixture.map((p) => (p.id === meId ? { ...p, ...me } : p));
  const meCamOff = !list.find((p) => p.id === meId)?.cam;
  const hostName = "박서준";

  const toggleHand = () => setHandRaised((raised) => !raised);

  /** 같은 패널을 다시 누르면 닫고, 다른 패널이면 그쪽으로 전환한다(프로토타입 togglePeople/toggleChat). */
  const togglePanel = (next: "people" | "chat") => {
    setPanelOpen((open) => !(open && panel === next));
    setPanel(next);
  };

  const addReaction = (emoji: string) => {
    const key = reactionSeq.current;
    reactionSeq.current += 1;
    setReactions((prev) => [...prev, { key, emoji, left: 20 + Math.random() * 60 }]);
    setReactMenuOpen(false);
    // zFloat 애니메이션(2.4s)이 끝나면 목록에서 제거한다.
    track(setTimeout(() => setReactions((prev) => prev.filter((r) => r.key !== key)), 2400));
  };

  /**
   * 나가기. 강사는 수업을 종료하는 것이라 사후 메모 작성으로 넘기고(프로토타입 endRoom),
   * 학생은 참여했던 강의 목록으로 돌아간다.
   */
  const leaveRoom = () => {
    router.push(isInstructor ? `/my-lectures/${sessionId}/note` : "/my-lectures");
  };

  const answerPrompt = (text: string) => {
    setPromptOpen(false);
    setPromptToast(text);
    track(setTimeout(() => setPromptToast(null), 2600));
  };

  return (
    <div className="relative flex h-screen flex-col bg-stage text-panel-text">
      {/* presence 응답 반영(세션 종료·강사 유예 안내) */}
      <SessionPresenceNotice
        reconnectStatus={presence.reconnectStatus}
        sessionEnded={presence.sessionEnded}
        error={presence.error}
      />
      {/* 최대 수업 시간 종료 임박 안내(서버 자동 종료와 짝) */}
      <SessionTimeWarning expiresAt={expiresAt ?? sessionExpiresAt ?? undefined} />
      {/* 상단 바 */}
      <div className="flex shrink-0 items-center gap-4 px-6 py-[13px]">
        <div className="text-xl font-black tracking-[-.5px] text-primary">ZANI</div>
        <div className="text-[14.5px] font-extrabold">{roomTitle}</div>
        <div className="flex-1" />
        <button
          type="button"
          onClick={() => setView(view === "gallery" ? "speaker" : "gallery")}
          className="inline-flex cursor-pointer items-center gap-1.5 whitespace-nowrap rounded-[11px] border border-[#262b42] bg-[#151830] px-4 py-[9px] font-sans text-[13.5px] font-extrabold text-panel-soft transition-colors hover:bg-room-control"
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
          label="채팅"
          onClick={() => togglePanel("chat")}
        >
          <ChatIcon />
        </PanelToggle>
        {/*
          강사만 수업을 끝낼 수 있다. 종료하면 모든 참가자가 나가므로 확인을 한 번 더 받는다.
          isInstructor 는 참가자 목록이 도착하기 전(connected=false) 시연용으로 true 가 되므로,
          되돌릴 수 없는 조작인 종료는 역할이 실제로 확정된 뒤에만 노출한다.
        */}
        {connected && isInstructor && (
          <EndSessionButton sessionId={sessionId} redirectTo={`/my-lectures/${sessionId}/note`} />
        )}
      </div>

      {/* 본문 */}
      <div className="flex min-h-0 flex-1 gap-3.5 p-3.5">
        <div className="flex min-w-0 flex-1 flex-col gap-3.5">
          {/* 스테이지 */}
          <div className="relative min-h-0 flex-1 overflow-hidden rounded-[18px] bg-stage">
            {sharing ? (
              /* 화면 공유 오버레이 — 갤러리/발표자 보기를 모두 덮는다 */
              <div className="absolute inset-0 z-[6] flex flex-col bg-stage">
                <div className="relative m-3.5 flex flex-1 items-center justify-center overflow-hidden rounded-[14px] border border-[#1e2740] bg-[#0f1626]">
                  <div className="px-5 text-center">
                    <MonitorIcon className="mx-auto text-[#4a5273]" />
                    <div className="mt-3.5 text-[15px] font-extrabold text-panel-soft">
                      내 화면을 공유하고 있어요
                    </div>
                    <div className="mt-[5px] text-[13px] text-room-status">
                      공유된 화면이 여기에 표시됩니다
                    </div>
                  </div>
                  <div className="z-stage-chip absolute left-4 top-4 font-bold">
                    <span className="size-2 rounded-full bg-primary" />내 화면
                  </div>
                </div>
                <div className="absolute bottom-4 left-1/2 z-[2] -translate-x-1/2">
                  <button
                    type="button"
                    onClick={() => setSharing(false)}
                    className="z-btn z-btn-danger rounded-full px-5 py-[11px] text-[13.5px]"
                  >
                    화면 공유 중지
                  </button>
                </div>
              </div>
            ) : view === "gallery" ? (
              <ParticipantGrid
                participants={galleryParticipants}
                currentParticipantId={localParticipantId ?? "p0"}
                isInstructor={isInstructor}
                narrow={panelOpen}
              />
            ) : (
              <>
                <div className="absolute inset-0 flex items-center justify-center [background:radial-gradient(ellipse_at_50%_32%,#191d33,#101322_78%)]">
                  <div className="flex size-[150px] items-center justify-center rounded-full bg-[linear-gradient(145deg,#12b585,#0b8a63)] text-[54px] font-extrabold text-[#eafff6] shadow-[0_0_0_12px_#10b98112,0_24px_60px_#10b98130]">
                    {hostName.charAt(0)}
                  </div>
                </div>
                <div className="pointer-events-none absolute inset-0">
                  <div className="z-stage-chip absolute left-4 top-4 font-bold">
                    강의: {hostName} 선생님
                  </div>
                  <div className="z-stage-chip absolute bottom-4 left-4 font-bold">
                    📶 {hostName} 선생님
                  </div>
                </div>
              </>
            )}

            {/* 카메라 꺼짐 안내 (학생) */}
            {!isInstructor && meCamOff && (
              <div className="absolute left-1/2 top-[18px] z-[5] -translate-x-1/2 animate-[zPop_.2s] rounded-[14px] border border-[#f3dc90] bg-warn-soft px-[18px] py-[11px] text-[13px] font-bold text-[#836607] shadow-[0_8px_24px_#0004]">
                📷 카메라가 10분 이상 꺼져 있어요. 켜면 학습 신호 분석에 참여할 수 있어요.{" "}
                <span className="font-semibold opacity-80">(이후 5분마다 안내)</span>
              </div>
            )}

            {/* 집단 알림 (강사) */}
            {isInstructor && alertOpen && (
              <div className="absolute right-2.5 top-2 z-[5] w-[290px] animate-[zPop_.2s] rounded-[18px] bg-surface p-[18px] text-ink shadow-[0_16px_44px_#0006]">
                <div className="mb-2.5 flex items-center justify-between">
                  <span className="z-pill bg-warn-soft px-3 py-[5px] text-[13px] text-warn">
                    ⚠ 개념 확인 필요
                  </span>
                  <button
                    type="button"
                    onClick={() => setAlertOpen(false)}
                    aria-label="알림 닫기"
                    className="cursor-pointer border-0 bg-transparent text-base text-ink-quiet"
                  >
                    ✕
                  </button>
                </div>
                <div className="flex flex-col gap-2.5">
                  <p className="m-0 text-[13.5px] font-bold leading-[1.5] text-ink">
                    학생 <b className="text-warn">30%</b>에게서 신호가 나타났어요.
                  </p>
                  <p className="m-0 text-[13.5px] leading-[1.5] text-ink-label">
                    잠시 속도를 늦추거나 짚어주면 좋아요.
                  </p>
                </div>
              </div>
            )}

            {/* 플로팅 반응 */}
            {reactions.map((r) => (
              <div
                key={r.key}
                aria-hidden="true"
                className="pointer-events-none absolute bottom-[90px] animate-[zFloat_2.4s_ease-out_forwards] text-[34px]"
                style={{ left: `${r.left}%` }}
              >
                {r.emoji}
              </div>
            ))}
          </div>

          <RoomControlBar
            me={me}
            sharing={sharing}
            reactMenuOpen={reactMenuOpen}
            mediaDisabled={!media.ready}
            microphoneBlocked={media.microphoneBlocked}
            cameraBlocked={media.cameraBlocked}
            microphones={media.microphones}
            cameras={media.cameras}
            activeMicrophoneId={media.activeMicrophoneId}
            activeCameraId={media.activeCameraId}
            onSelectMicrophone={media.selectMicrophone}
            onSelectCamera={media.selectCamera}
            onToggleMic={media.toggleMicrophone}
            onToggleCam={media.toggleCamera}
            onToggleShare={() => setSharing((v) => !v)}
            onToggleHand={toggleHand}
            onToggleReactMenu={() => setReactMenuOpen((v) => !v)}
            onReact={addReaction}
            onLeave={leaveRoom}
          />
        </div>

        {panelOpen && (
          <RoomSidePanel
            panel={panel}
            participants={list}
            messages={publicMessages}
            meId={meId}
            isInstructor={isInstructor}
          />
        )}
      </div>

      {/* 확인 프롬프트 (학생) */}
      {promptOpen && (
        <div className="absolute bottom-24 left-1/2 z-50 w-[420px] -translate-x-1/2 animate-[zPop_.2s] rounded-[20px] bg-surface p-[22px] text-ink shadow-[0_20px_50px_#0008]">
          <div className="mb-1.5 flex items-center justify-between">
            <span className="text-base font-extrabold">잠깐 확인할게요 ✋</span>
            <span className="flex h-[34px] min-w-[34px] items-center justify-center rounded-[10px] bg-primary-soft px-2 font-mono text-[15px] font-black text-primary">
              30
            </span>
          </div>
          <p className="mb-4 text-sm text-ink-sub">
            방금 설명한 내용, 지금 어떤가요? 응답은 강사에게 개인별로 공개되지 않아요.
          </p>
          <div className="flex gap-2.5">
            <button
              type="button"
              onClick={() => answerPrompt("응답을 보냈어요. 고마워요!")}
              className="z-btn flex-1 rounded-[14px] border-[1.5px] border-[#d4f0e5] bg-primary-mint py-3.5 text-primary-dark"
            >
              👍 이해했어요
            </button>
            <button
              type="button"
              onClick={() => answerPrompt("응답을 보냈어요. 곧 짚어드릴게요.")}
              className="z-btn flex-1 rounded-[14px] border-[1.5px] border-[#f6e3a7] bg-warn-soft py-3.5 text-warn-text"
            >
              🤔 헷갈려요
            </button>
            <button
              type="button"
              onClick={() => answerPrompt("응답을 보냈어요. 관련 구간을 리포트에 담아둘게요.")}
              className="z-btn flex-1 rounded-[14px] border-[1.5px] border-line-muted bg-primary-softer py-3.5 text-ink-muted"
            >
              😅 놓쳤어요
            </button>
          </div>
        </div>
      )}
      {promptToast && (
        <div
          role="status"
          className="absolute bottom-24 left-1/2 z-50 -translate-x-1/2 animate-[zPop_.2s] rounded-[14px] border border-room-edge bg-[#1e2138] px-5 py-3 text-[13px] text-panel-soft"
        >
          {promptToast}
        </div>
      )}
    </div>
  );
}
