"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { ChatIcon, MonitorIcon, PeopleIcon } from "@/shared/ui";
import {
  CoachingPromptPanel,
  useCameraGuidePrompt,
  usePostureGuidePrompt,
  useUnderstandingCheckPrompt,
  type AnalysisAvailability,
  type CameraGuideCause,
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
import { RoomProvider, useRoomConnection } from "./RoomProvider";
import { SessionTimeWarning } from "./components/room/SessionTimeWarning";
import { EndSessionButton } from "./components/room/EndSessionButton";
import { SessionPresenceNotice } from "./components/room/SessionPresenceNotice";
import { AttentionCameraSource } from "./components/room/AttentionCameraSource";
import { AnalysisStatusNotice } from "./components/room/AnalysisStatusNotice";
import { CoachingStatusNotice } from "./components/room/CoachingStatusNotice";
import { useCoachingStatus } from "./useCoachingStatus";
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

/** 카메라 안내 문구는 원인별로 갈린다(기준 문서 §5.2). 상태는 셋 다 CAMERA_OFF 하나다. */
const CAMERA_GUIDE_COPY: Record<CameraGuideCause, { title: string; body: string }> = {
  disabled: {
    title: "카메라를 켜주세요 📷",
    body: "수업 참여도를 확인하려면 카메라가 필요해요. 지금 켜실 수 있나요?",
  },
  denied: {
    title: "카메라 권한이 필요해요 🔒",
    body: "브라우저에서 카메라 권한을 허용해주세요. 주소창 옆 자물쇠 아이콘에서 바꿀 수 있어요.",
  },
  muted: {
    title: "카메라를 사용할 수 없어요 ⚠️",
    body: "다른 앱이 카메라를 사용 중인지 확인해주세요.",
  },
};

const UNDERSTANDING_CHECK_FEEDBACK: Record<UnderstandingCheckResponse, string> = {
  OK: "응답을 보냈어요.",
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
  // 역할은 백엔드가 토큰에 심은 값(useRoomParticipants)에서 파생한다. 프론트가 정하지 않는다.
  // 아직 room 이 붙지 않은 시연 상태에서는 강사 화면을 기준으로 본다.
  const connected = tileParticipants.length > 0;
  const isInstructor =
    !connected ||
    tileParticipants.find((p) => p.id === localParticipantId)?.role === "instructor";
  const [view, setView] = useState<"gallery" | "speaker">("gallery");
  const [panel, setPanel] = useState<"people" | "chat">("people");
  const [panelOpen, setPanelOpen] = useState(false);
  const [handRaised, setHandRaised] = useState(false);
  // 마이크·카메라는 로컬 state 가 아니라 실제 publish 상태를 쓴다. 손들기는 아직 fixture(WebSocket 소관).
  const me = { mic: media.microphoneEnabled, cam: media.cameraEnabled, hand: handRaised };
  const [reactMenuOpen, setReactMenuOpen] = useState(false);
  const [sharing, setSharing] = useState(false);
  const [promptToast, setPromptToast] = useState<string | null>(null);
  // 판정 상태가 아니라 접힌 가용 상태만 들고 있다. 카메라·검출기가 실제로 바뀔 때만 갱신된다.
  const [analysisAvailability, setAnalysisAvailability] = useState<AnalysisAvailability>("ACTIVE");
  // 팁을 받는 쪽이 강사라 강사 화면에서만 폴링한다. 팁 카드 배선은 86 소관이다.
  //
  // 역할이 확정되기 전(connected=false)에는 isInstructor 가 true 이므로 그것만 보면 학생도
  // 잠깐 강사 전용 엔드포인트를 두드리고 강사용 배지를 보게 된다. 학생 판정은 !isInstructor
  // 라 기본값이 안전한 쪽이지만 강사 기능은 반대라, connected 를 함께 본다.
  const isConfirmedInstructor = connected && isInstructor;
  const coaching = useCoachingStatus({ sessionId, enabled: isConfirmedInstructor });
  const understandingCheck = useUnderstandingCheckPrompt({ sessionId });
  const postureGuide = usePostureGuidePrompt();
  // 트랙 muted(다른 앱 점유)는 아직 미디어 훅이 알려주지 않아 원인에 들어오지 않는다.
  const cameraGuide = useCameraGuidePrompt({
    sessionId,
    camera: media.cameraPermissionDenied ? "denied" : media.cameraEnabled ? "on" : "off",
    // 학생 프롬프트라 강사 화면에서는 돌리지 않는다. 역할이 확인되기 전에는 isInstructor 가
    // true 라, 켜지지 않는 쪽이 기본값이다(AttentionCameraSource 와 같은 판단).
    enabled: !isInstructor,
  });
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

  // room 에 참가자가 없으면 갤러리가 빈 화면이 되므로 사이드 패널과 같은 시연용 픽스처로 채운다.
  // 실제 참가자가 한 명이라도 잡히면 그쪽이 우선한다(WebSocket·미디어 연동 시 이 분기를 제거).
  const galleryParticipants = connected ? tileParticipants : participantTiles;

  // 사이드 패널 people/chat 목록은 아직 fixture 기반(WebSocket·57 소관).
  const meId = isInstructor ? "p0" : "p7";
  const list = participantsFixture.map((p) => (p.id === meId ? { ...p, ...me } : p));
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

  const answerPrompt = async (value: UnderstandingCheckResponse) => {
    const sent = await understandingCheck.respond(value);
    if (!sent) return; // 전송 실패 — 조용히 넘어간다(수업 진행 우선).
    setPromptToast(UNDERSTANDING_CHECK_FEEDBACK[value]);
    track(setTimeout(() => setPromptToast(null), 2600));
  };

  return (
    <div className="relative flex h-screen flex-col bg-stage text-panel-text">
      {/*
        참여도 판정은 학생 화면에서만 돌린다. 강사는 집계를 보는 쪽이라 판정 대상이 아니다.
        역할이 확인되기 전에는 isInstructor 가 true 라, 판정이 켜지지 않는 쪽이 기본값이다.

        수업별 분석 동의는 아직 코드에 없다. 지금은 판정 결과가 기기 밖으로 나가지 않아
        문제되지 않지만, 판정 이벤트 전송을 붙이는 사람은 전송에 조건을 거는 것으로 끝내지 말고
        이 마운트 조건에 동의 여부를 반드시 함께 넣어야 한다. 그러지 않으면 동의하지 않은
        학생의 기기에서도 판정이 계속 돌아간다.
      */}
      {!isInstructor && (
        <AttentionCameraSource
          active={media.ready && media.cameraEnabled}
          denied={media.cameraPermissionDenied}
          onAvailabilityChange={setAnalysisAvailability}
        />
      )}
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
        {/* 분석 가용 상태(76). 학생에게 동작 여부만 알리고 점수·개별 판정은 담지 않는다. */}
        {!isInstructor && <AnalysisStatusNotice availability={analysisAvailability} />}
        {/* 코칭 가용 상태(76). 팁을 받는 쪽이 강사라 역할이 확정된 강사에게만 알린다. */}
        {isConfirmedInstructor && <CoachingStatusNotice availability={coaching.availability} />}
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

            {/*
              카메라 꺼짐 안내는 카메라 안내 프롬프트(81)가 원인별로 맡는다. 여기 있던 프로토타입
              배너는 "10분 이상·이후 5분마다"라는 옛 규칙이라 확정된 1분 발동·5분 재권유와
              어긋나고 문구도 겹쳐 제거했다(76: 중복 구현하지 않는다).
            */}

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

      {/* 확인 프롬프트 (학생 전용 — 강사는 판정 대상이 아니다) */}
      {!isInstructor && understandingCheck.prompt && (
        <CoachingPromptPanel
          title="잠깐 확인할게요 ✋"
          body="방금 설명한 내용, 지금 어떤가요? 응답은 강사에게 개인별로 공개되지 않아요."
          remainingMs={understandingCheck.prompt.remainingMs}
          durationMs={understandingCheck.prompt.durationMs}
          onSelect={answerPrompt}
          options={[
            {
              value: "OK",
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
      {/* 자세 안내 (학생 전용) — 확인 버튼 하나뿐이고 서버로 보내지 않는다 */}
      {!isInstructor && postureGuide.prompt && (
        <CoachingPromptPanel
          title="얼굴이 잘 보이지 않아요 🙂"
          body="카메라에 얼굴이 나오도록 조정해주세요."
          remainingMs={postureGuide.prompt.remainingMs}
          durationMs={postureGuide.prompt.durationMs}
          onSelect={postureGuide.acknowledge}
          options={[
            {
              value: "ACKNOWLEDGED",
              label: "확인",
              toneClassName: "border-[#d4f0e5] bg-primary-mint text-primary-dark",
            },
          ]}
        />
      )}

      {/* 카메라 안내 (학생 전용) — 권한 거부·트랙 muted 는 답을 물어도 소용이 없어 확인만 받는다 */}
      {!isInstructor && cameraGuide.prompt && (
        <CoachingPromptPanel
          title={CAMERA_GUIDE_COPY[cameraGuide.prompt.cause].title}
          body={CAMERA_GUIDE_COPY[cameraGuide.prompt.cause].body}
          remainingMs={cameraGuide.prompt.remainingMs}
          durationMs={cameraGuide.prompt.durationMs}
          onSelect={cameraGuide.answer}
          options={
            cameraGuide.prompt.cause === "disabled"
              ? [
                  {
                    value: "WILL_ENABLE",
                    label: "지금 켤게요",
                    toneClassName: "border-[#d4f0e5] bg-primary-mint text-primary-dark",
                  },
                  {
                    value: "CANNOT_ENABLE",
                    label: "못 켜요",
                    toneClassName: "border-line-muted bg-primary-softer text-ink-muted",
                  },
                ]
              : [
                  {
                    value: "WILL_ENABLE",
                    label: "확인",
                    toneClassName: "border-[#d4f0e5] bg-primary-mint text-primary-dark",
                  },
                ]
          }
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
