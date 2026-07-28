"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { ChatIcon, MonitorIcon, PeopleIcon } from "@/shared/ui";
import {
  CoachingPromptPanel,
  useUnderstandingCheckPrompt,
  type UnderstandingCheckResponse,
} from "@/domains/attention";
import {
  participantTiles,
  participants as participantsFixture,
  publicMessages,
} from "./fixtures";
import { ParticipantGrid } from "./components/room/ParticipantGrid";
import { useRoomParticipants } from "./useRoomParticipants";
import { RoomControlBar } from "./components/room/RoomControlBar";
import { RoomSidePanel } from "./components/room/RoomSidePanel";
import { RoomProvider } from "./RoomProvider";

/**
 * SC-09 실시간 강의실 (어두운 테마). LiveKit room connection is attached here;
 * media track publishing remains out of scope.
 */
type RoomScreenProps = {
  sessionId: string;
  roomTitle?: string;
};

type FloatingReaction = { key: number; emoji: string; left: number };

const UNDERSTANDING_CHECK_FEEDBACK: Record<UnderstandingCheckResponse, string> = {
  UNDERSTOOD: "응답을 보냈어요.",
  CONFUSED: "응답을 보냈어요.",
  MISSED: "응답을 보냈어요.",
};

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

export function RoomScreen({ sessionId, roomTitle }: RoomScreenProps) {
  return (
    <RoomProvider sessionId={sessionId}>
      <RoomScreenContent sessionId={sessionId} roomTitle={roomTitle} />
    </RoomProvider>
  );
}

function RoomScreenContent({ sessionId, roomTitle = "React 상태관리 심화" }: RoomScreenProps) {
  const router = useRouter();
  const { participants: tileParticipants, localParticipantId } = useRoomParticipants();
  const [view, setView] = useState<"gallery" | "speaker">("gallery");
  const [panel, setPanel] = useState<"people" | "chat">("people");
  const [panelOpen, setPanelOpen] = useState(false);
  const [me, setMe] = useState({ mic: true, cam: true, hand: false });
  const [reactMenuOpen, setReactMenuOpen] = useState(false);
  const [sharing, setSharing] = useState(false);
  const [promptToast, setPromptToast] = useState<string | null>(null);
  const understandingCheck = useUnderstandingCheckPrompt({ sessionId });
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

  const toggleMe = (k: "mic" | "cam" | "hand") => setMe((p) => ({ ...p, [k]: !p[k] }));

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

  const answerPrompt = async (value: UnderstandingCheckResponse) => {
    const sent = await understandingCheck.respond(value);
    if (!sent) return; // 전송 실패 — 조용히 넘어간다(수업 진행 우선).
    setPromptToast(UNDERSTANDING_CHECK_FEEDBACK[value]);
    track(setTimeout(() => setPromptToast(null), 2600));
  };

  return (
    <div className="relative flex h-screen flex-col bg-stage text-panel-text">
      {/* 상단 바 */}
      <div className="flex shrink-0 items-center gap-4 px-6 py-[13px]">
        <div className="text-xl font-black tracking-[-.5px] text-primary">ZANI</div>
        <div className="text-[14.5px] font-extrabold">{roomTitle}</div>
        <div className="flex-1" />
        {/* TODO(S15P11A105-75): 판정 파이프라인이 NEEDS_CHECK 를 감지하면 이 버튼 대신 그쪽에서 trigger 를 호출한다. */}
        {!isInstructor && process.env.NODE_ENV !== "production" && (
          <button
            type="button"
            onClick={() => understandingCheck.trigger(`dev-${Date.now()}`)}
            className="rounded-[11px] border border-[#262b42] bg-[#151830] px-3 py-[9px] font-sans text-[12px] text-panel-muted"
          >
            확인 프롬프트 테스트
          </button>
        )}
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
            onToggleMic={() => toggleMe("mic")}
            onToggleCam={() => toggleMe("cam")}
            onToggleShare={() => setSharing((v) => !v)}
            onToggleHand={() => toggleMe("hand")}
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
      {understandingCheck.prompt && (
        <CoachingPromptPanel
          title="잠깐 확인할게요 ✋"
          body="방금 설명한 내용, 지금 어떤가요? 응답은 강사에게 개인별로 공개되지 않아요."
          remainingMs={understandingCheck.prompt.remainingMs}
          durationMs={understandingCheck.prompt.durationMs}
          onSelect={answerPrompt}
          options={[
            {
              value: "UNDERSTOOD",
              label: "이해했어요",
              emoji: "👍",
              toneClassName: "border-[#d4f0e5] bg-primary-mint text-primary-dark",
            },
            {
              value: "CONFUSED",
              label: "헷갈려요",
              emoji: "🤔",
              toneClassName: "border-[#f6e3a7] bg-warn-soft text-warn-text",
            },
            {
              value: "MISSED",
              label: "놓쳤어요",
              emoji: "😅",
              toneClassName: "border-line-muted bg-primary-softer text-ink-muted",
            },
          ]}
        />
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
