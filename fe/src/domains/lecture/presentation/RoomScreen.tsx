"use client";

import { useCallback, useEffect, useLayoutEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { useRouter } from "next/navigation";
import { CameraIcon, ChatIcon, CloseIcon, MicIcon, PeopleIcon, ScreenShareIcon } from "@/shared/ui";
import {
  CoachingPromptPanel,
  INITIAL_ATTENTION_COACHING_STATE,
  reduceAttentionCoaching,
  useCameraGuidePrompt,
  usePostureGuidePrompt,
  useUnderstandingCheckPrompt,
  type AnalysisAvailability,
  type CameraGuideCause,
  type DetectorOutput,
  type UnderstandingCheckResponse,
} from "@/domains/attention";
import { SessionChannelProvider, useSessionChat } from "@/domains/interaction";
import { useAuth } from "@/domains/auth";
import { endSession, EndSessionRequestError } from "../infrastructure/endSessionApi";
import { ParticipantGrid } from "./components/room/ParticipantGrid";
import { RoomRoster } from "./components/room/RoomRoster";
import { useRoomParticipants } from "./useRoomParticipants";
import { useParticipantVideos } from "./useParticipantVideos";
import { useRemoteAudio } from "./useRemoteAudio";
import { RoomControlBar } from "./components/room/RoomControlBar";
import { RoomSidePanel } from "./components/room/RoomSidePanel";
import { RoomProvider, useRoomConnection } from "./RoomProvider";
import { SessionTimeWarning } from "./components/room/SessionTimeWarning";
import { SessionPresenceNotice } from "./components/room/SessionPresenceNotice";
import { AttentionCameraSource } from "./components/room/AttentionCameraSource";
import { AnalysisStatusNotice } from "./components/room/AnalysisStatusNotice";
import { CoachingStatusNotice } from "./components/room/CoachingStatusNotice";
import { CoachTipCard } from "./components/room/CoachTipCard";
import { useCoachingStatus } from "./useCoachingStatus";
import { useCoachTipCard } from "./useCoachTipCard";
import { useDocumentPictureInPicture } from "./useDocumentPictureInPicture";
import { useRoomMediaControls } from "./useRoomMediaControls";
import { useScreenShare } from "./useScreenShare";
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
};

type FloatingReaction = { key: number; emoji: string; left: number };

/**
 * 강사가 방을 종료했을 때(또는 미복귀 자동 종료) 학생을 바로 튕겨내지 않고, 종료 안내를
 * 잠깐 보여준 뒤 내보내기까지의 시간(ms). 강사 본인은 사후 메모로 곧바로 이동하므로 해당 없다.
 */
const ENDED_KICK_DELAY_MS = 4_000;

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

/** PiP 미니 창 하단 컨트롤 버튼(원형). 끔 상태(마이크·카메라)는 danger, 그 외는 room-control. */
const pipBtn =
  "flex size-10 shrink-0 cursor-pointer items-center justify-center rounded-full border border-white/10 text-white transition-[filter] hover:brightness-125 disabled:cursor-not-allowed disabled:opacity-50";

/** 미니 창(Picture-in-Picture) 팝아웃 아이콘. */
function PopOutIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <rect x="3" y="4" width="18" height="14" rx="2" stroke="currentColor" strokeWidth="1.8" />
      <rect x="12" y="10" width="7" height="6" rx="1" fill="currentColor" />
    </svg>
  );
}

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

export function RoomScreen({ sessionId, roomTitle, expiresAt }: RoomScreenProps) {
  return (
    <RoomProvider sessionId={sessionId}>
      {/* 업무 이벤트(채팅·손들기·반응)는 LiveKit 이 아니라 STOMP 채널로 오간다. 미디어와 수명이
          달라 별도 Provider 로 둔다 — 한쪽이 끊겨도 다른 쪽은 이어진다. */}
      <SessionChannelProvider sessionId={sessionId}>
        <RoomScreenContent
          sessionId={sessionId}
          roomTitle={roomTitle}
          expiresAt={expiresAt}
        />
      </SessionChannelProvider>
    </RoomProvider>
  );
}

function RoomScreenContent({
  sessionId,
  roomTitle,
  expiresAt,
}: RoomScreenProps) {
  const router = useRouter();
  const { accessToken } = useAuth();
  // 종료 예정 시각은 강의실 진입 시 미디어 토큰 응답으로 받는다. prop 은 테스트·스토리북 강제 지정용이다.
  const { room, sessionExpiresAt, sessionTitle, connectionState } = useRoomConnection();
  const media = useRoomMediaControls(sessionId);
  // 원격 참가자 마이크 소리를 실제로 들리게 한다. 타일 video 는 전부 muted 라 이 배선이 없으면 무음이다.
  useRemoteAudio(room);
  // 서버는 이 heartbeat 로 강사 5분 유예·자동 종료를 판단한다(가이드 §12).
  const presence = useSessionPresence(sessionId);
  const { participants: tileParticipants, localParticipantId } = useRoomParticipants();
  // 로컬·원격 카메라 화면을 타일에 붙인다. 훅은 여기서 한 번만 부르고 ref 를 내려보낸다.
  const participantVideos = useParticipantVideos();
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
  // 화면 공유는 실제 LiveKit 트랙 + 서버 활성 슬롯을 쓴다. 로컬 로 토글하던 시연 상태를 대체한다.
  const {
    sharing: isSharing,
    active: shareActive,
    blocked: shareBlocked,
    activeIdentity: shareActiveIdentity,
    attachScreen,
    toggle: toggleScreenShare,
  } = useScreenShare(sessionId);
  // 공유 중 강의방을 브라우저 밖 다른 앱 위에도 띄우는 미니 창(구글미트식).
  const {
    supported: pipSupported,
    pipWindow,
    open: openPip,
    close: closePip,
  } = useDocumentPictureInPicture();
  const [promptToast, setPromptToast] = useState<string | null>(null);
  // 판정 상태가 아니라 접힌 가용 상태만 들고 있다. 카메라·검출기가 실제로 바뀔 때만 갱신된다.
  const [analysisAvailability, setAnalysisAvailability] = useState<AnalysisAvailability>("ACTIVE");
  // 팁을 받는 쪽이 강사라 강사 화면에서만 폴링한다. 팁 카드 배선은 86 소관이다.
  //
  // 역할이 확정되기 전(connected=false)에는 isInstructor 가 true 이므로 그것만 보면 학생도
  // 잠깐 강사 전용 엔드포인트를 두드리고 강사용 배지를 보게 된다. 학생 판정은 !isInstructor
  // 라 기본값이 안전한 쪽이지만 강사 기능은 반대라, connected 를 함께 본다.
  const isConfirmedInstructor = connected && isInstructor;
  // 채팅 발신자 표시는 LiveKit 이 알려주는 내 identity·이름을 그대로 쓴다. 봉투의 sender.identity 가
  // participant.identity 와 같은 값이라(63 계약) 내 메시지 판정이 이 한 값으로 끝난다.
  //
  // 강사 배지에 isInstructor 가 아니라 isConfirmedInstructor 를 쓰는 이유: 역할 확정 전에는
  // isInstructor 가 true 라, 학생이 보낸 첫 메시지에 강사 배지가 붙는다.
  const chat = useSessionChat({
    myIdentity: localParticipantId,
    myDisplayName: tileParticipants.find((p) => p.id === localParticipantId)?.name ?? "나",
    amInstructor: isConfirmedInstructor,
  });
  // 팁 카드는 폴러를 따로 두지 않는다. 그 폴링이 곧 트리거 판정이라 두 번 돌면 분모 조회가
  // 두 배가 되고 쿨타임을 두 주체가 소모한다(86 요구사항).
  const coachTip = useCoachTipCard();
  const coaching = useCoachingStatus({
    sessionId,
    enabled: isConfirmedInstructor,
    onResult: coachTip.accept,
  });
  const attentionCoachingStateRef = useRef(INITIAL_ATTENTION_COACHING_STATE);
  const resetAttentionCoaching = useCallback(() => {
    attentionCoachingStateRef.current = INITIAL_ATTENTION_COACHING_STATE;
  }, []);
  const understandingCheck = useUnderstandingCheckPrompt({
    sessionId,
    onClosed: resetAttentionCoaching,
  });
  const postureGuide = usePostureGuidePrompt({ onClosed: resetAttentionCoaching });
  // 트랙 muted(다른 앱 점유)는 아직 미디어 훅이 알려주지 않아 원인에 들어오지 않는다.
  const cameraGuide = useCameraGuidePrompt({
    sessionId,
    camera: media.cameraPermissionDenied ? "denied" : media.cameraEnabled ? "on" : "off",
    // 학생 프롬프트라 강사 화면에서는 돌리지 않는다. 역할이 확인되기 전에는 isInstructor 가
    // true 라, 켜지지 않는 쪽이 기본값이다(AttentionCameraSource 와 같은 판단).
    enabled: !isInstructor,
    onClosed: resetAttentionCoaching,
  });
  const promptVisible =
    understandingCheck.prompt !== null ||
    postureGuide.prompt !== null ||
    cameraGuide.prompt !== null;
  const promptVisibleRef = useRef(promptVisible);
  useLayoutEffect(() => {
    promptVisibleRef.current = promptVisible;
  }, [promptVisible]);
  const triggerUnderstandingCheck = understandingCheck.trigger;
  const triggerPostureGuide = postureGuide.trigger;
  const handleAttentionDetection = useCallback(
    (output: DetectorOutput) => {
      const decision = reduceAttentionCoaching(attentionCoachingStateRef.current, {
        output,
        promptVisible: promptVisibleRef.current,
      });
      attentionCoachingStateRef.current = decision.state;
      if (decision.prompt === "UNDERSTANDING_CHECK") {
        triggerUnderstandingCheck(`understanding-${Date.now()}`);
      } else if (decision.prompt === "POSTURE_GUIDE") {
        triggerPostureGuide(`posture-${Date.now()}`);
      }
    },
    [triggerPostureGuide, triggerUnderstandingCheck],
  );
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

  // 내가 공유를 시작하면(화면 선택까지 끝나 트랙이 올라온 뒤) 미니 창을 자동으로 띄운다.
  // 화면 선택 직후라 사용자 제스처가 살아 있어 대개 허용된다. 창을 직접 닫으면 isSharing 은 그대로라 다시 뜨지 않는다.
  useEffect(() => {
    if (isSharing && pipSupported) {
      void openPip().catch(() => {});
    }
  }, [isSharing, pipSupported, openPip]);

  // 공유가 끝나면 떠 있는 미니 창을 닫는다.
  useEffect(() => {
    if (!shareActive) {
      closePip();
    }
  }, [shareActive, closePip]);

  // 공유 중 다른 탭·창으로 전환하면(document.hidden) 미니 창을 띄운다. 사용자 제스처가 없으면 브라우저가 막을 수 있어
  // best-effort 로 시도하고, 팝아웃 버튼이 확실한 경로다.
  useEffect(() => {
    if (!shareActive || !pipSupported) {
      return;
    }
    const handleVisibility = () => {
      if (document.hidden) {
        void openPip().catch(() => {});
      }
    };
    document.addEventListener("visibilitychange", handleVisibility);
    return () => document.removeEventListener("visibilitychange", handleVisibility);
  }, [shareActive, pipSupported, openPip]);

  const track = useCallback((id: ReturnType<typeof setTimeout>) => {
    timers.current.push(id);
  }, []);

  // 갤러리는 LiveKit 이 알려주는 실제 참가자만 보여준다. 아직 아무도 없으면 빈 화면이 맞다 —
  // 시연용 픽스처로 채우면 들어오지 않은 학생이 참가 중인 것처럼 보인다.
  const galleryParticipants = tileParticipants;

  // 사이드 패널 사람 목록도 갤러리와 같은 실제 참가자를 쓴다. 내 마이크·카메라는 LiveKit 반영보다
  // 로컬 토글이 먼저 움직이므로, 내 행만 로컬 상태로 덮어 즉시 반응하게 한다.
  // 손들기는 업무 WebSocket 소관이라 아직 항상 내려간 상태다.
  const meId = localParticipantId;
  const list = tileParticipants.map((participant) => ({
    id: participant.id,
    name: participant.name,
    color: participant.color,
    host: participant.role === "instructor",
    cam: participant.cameraEnabled,
    mic: participant.microphoneEnabled,
    hand: participant.handRaised,
    ...(participant.id === meId ? me : {}),
  }));
  // 아직 모르는 상태와 "제목 없음" 을 구분한다. 연결이 끝났는데도 제목이 없으면 서버가 안 내려주는 구성이므로
  // 자리만 잡고 기다리지 않고 기본 문구를 쓴다. 그러지 않으면 스켈레톤이 영원히 뛴다.
  const title = roomTitle ?? sessionTitle ?? (connectionState === "connected" ? "수업" : null);
  const host = tileParticipants.find((participant) => participant.role === "instructor");
  const hostName = host?.name ?? list.find((p) => p.host)?.name ?? "";
  // 공유 중인 참가자의 표시 이름. LiveKit 참가자 목록에서 identity 로 찾는다(내 공유면 오버레이가 "내 화면"으로 덮는다).
  const activeSharerName =
    tileParticipants.find((participant) => participant.id === shareActiveIdentity)?.name ?? "참가자";

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

  const [leaveConfirming, setLeaveConfirming] = useState(false);
  const [endingSession, setEndingSession] = useState(false);
  const [endSessionError, setEndSessionError] = useState<string | null>(null);
  // 이동이 시작된 뒤의 중복 클릭·중복 이동을 막는다(라우팅 전까지 컴포넌트가 살아 있다).
  const leaveRequested = useRef(false);

  /**
   * 나가기. 학생은 참여했던 강의 목록으로 바로 돌아가고, 강사는 수업을 종료하는 것이라 모든
   * 참가자에게 영향을 주므로 말풍선으로 한 번 더 확인받는다(종료 후 사후 메모 작성으로 이동).
   *
   * 역할이 확정되기 전(connected=false)에는 isInstructor 가 시연용 true 라 종료를 묻지 않고
   * 기존 경로 그대로 이동만 한다(EndSessionButton 이 쓰던 판단과 같다).
   */
  const leaveRoom = () => {
    if (leaveRequested.current) return;
    if (isConfirmedInstructor) {
      setEndSessionError(null);
      setLeaveConfirming(true);
      return;
    }
    leaveRequested.current = true;
    router.push(isInstructor ? `/my-lectures/${sessionId}/note` : "/my-lectures");
  };

  /** 말풍선의 "종료". 세션을 즉시 ENDED 로 전환한 뒤 사후 메모 작성으로 이동한다. */
  const confirmEndSession = async () => {
    if (leaveRequested.current || endingSession) return;
    if (!accessToken) {
      setEndSessionError("로그인이 풀렸습니다. 다시 로그인한 뒤 종료해 주세요.");
      return;
    }
    setEndingSession(true);
    setEndSessionError(null);
    try {
      await endSession(sessionId, accessToken);
      leaveRequested.current = true;
      router.push(`/my-lectures/${sessionId}/note`);
    } catch (failure) {
      setEndingSession(false);
      setEndSessionError(
        failure instanceof EndSessionRequestError && failure.status === 403
          ? "수업을 연 강사만 종료할 수 있습니다."
          : "수업을 종료하지 못했습니다. 잠시 후 다시 시도해 주세요.",
      );
    }
  };

  // 종료된 수업에 남아 있는 참가자를 내보낸다 — 강사 미복귀 자동 종료든, 강사가 방을 닫았든,
  // 다른 화면에서의 종료든 presence 가 종료를 알리는 즉시. 강사는 사후 메모 작성으로 바로 이동하고,
  // 학생은 "곧 종료" 안내(SessionPresenceNotice)를 잠깐 본 뒤 강의 목록으로 나간다.
  useEffect(() => {
    if (!presence.sessionEnded || leaveRequested.current) return;
    if (isInstructor) {
      leaveRequested.current = true;
      router.push(`/my-lectures/${sessionId}/note`);
      return;
    }
    const timer = setTimeout(() => {
      leaveRequested.current = true;
      router.push("/my-lectures");
    }, ENDED_KICK_DELAY_MS);
    return () => clearTimeout(timer);
  }, [presence.sessionEnded, isInstructor, router, sessionId]);

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

        10초 관측은 여기서부터 서버로 나간다. 프레임·랜드마크·확률은 기기 밖으로 나가지 않고
        판정 결과 7종만 실린다(attention 도메인 전송 어댑터가 계약 밖 필드를 담지 않는다).
      */}
      {!isInstructor && (
        <AttentionCameraSource
          sessionId={sessionId}
          active={media.ready && media.cameraEnabled}
          denied={media.cameraPermissionDenied}
          onAvailabilityChange={setAnalysisAvailability}
          onDetection={handleAttentionDetection}
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
        {/*
          강의명은 서버가 미디어 토큰 응답으로 내려주므로 연결이 끝나기 전에는 알 수 없다.
          그 동안 "수업" 같은 최종값처럼 보이는 문구를 그리면 제목이 바뀌는 것처럼 보인다. 자리만 잡아 둔다.
          prop 은 테스트·스토리북 강제 지정용이다.
        */}
        <div className="text-[14.5px] font-extrabold">
          {title ?? (
            <span
              data-testid="room-title-loading"
              aria-label="강의명을 불러오는 중"
              className="inline-block h-[15px] w-28 animate-pulse rounded bg-white/15 align-middle"
            />
          )}
        </div>
        <div className="flex-1" />
        {/* 분석 가용 상태(76). 학생에게 동작 여부만 알리고 점수·개별 판정은 담지 않는다. */}
        {!isInstructor && <AnalysisStatusNotice availability={analysisAvailability} />}
        {/* 코칭 가용 상태(76). 팁을 받는 쪽이 강사라 역할이 확정된 강사에게만 알린다. */}
        {isConfirmedInstructor && <CoachingStatusNotice availability={coaching.availability} />}
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
            {shareActive ? (
              /* 화면 공유 오버레이 — 갤러리/발표자 보기를 모두 덮는다. 로컬·원격 트랙을 그대로 붙인다. */
              <div className="absolute inset-0 z-[6] flex flex-col bg-stage">
                <div className="relative m-3.5 flex flex-1 items-center justify-center overflow-hidden rounded-[14px] border border-[#1e2740] bg-[#0f1626]">
                  <video
                    ref={attachScreen}
                    autoPlay
                    muted
                    playsInline
                    data-testid="screen-share-video"
                    className="size-full object-contain"
                  />
                  <div className="z-stage-chip absolute left-4 top-4 flex items-center gap-1.5 font-bold">
                    <span className="size-2 rounded-full bg-primary" />
                    {isSharing ? "내 화면" : `${activeSharerName} 님의 화면`}
                  </div>
                </div>
                {/* 공유 중 강의방 미니 레이아웃(구글미트식). PiP 창이 열려 있으면 인앱 대신 그 창으로 옮긴다(아래 portal).
                    같은 타일을 두 곳에 동시에 그리지 않는다 — 카메라 트랙은 identity당 요소 하나에만 붙기 때문. */}
                {galleryParticipants.length > 0 && !pipWindow && (
                  <div className="absolute right-5 top-5 z-[8] flex flex-col items-end gap-2">
                    {pipSupported && (
                      <button
                        type="button"
                        onClick={() => void openPip()}
                        title="미니 창으로 보기"
                        aria-label="미니 창으로 보기"
                        className="flex size-8 items-center justify-center rounded-lg border border-room-line bg-[#0e1020cc] text-panel-soft backdrop-blur-[6px] transition-colors hover:bg-room-control"
                      >
                        <PopOutIcon />
                      </button>
                    )}
                    <RoomRoster
                      participants={galleryParticipants}
                      videoRefFor={participantVideos.refFor}
                      localParticipantId={localParticipantId}
                      testId="screen-share-roster"
                      className="flex max-h-[calc(100%-140px)] w-[150px] flex-col gap-2 overflow-y-auto rounded-2xl border border-room-line bg-[#0e1020cc] p-2 shadow-[0_12px_32px_rgba(0,0,0,.45)] backdrop-blur-[6px] sm:w-[184px]"
                    />
                  </div>
                )}
                {/* 미니 창이 열려 있으면 강의방을 그 창(다른 앱 위에도 뜨는)으로 그린다. */}
                {pipWindow &&
                  galleryParticipants.length > 0 &&
                  createPortal(
                    <div className="flex h-screen flex-col bg-stage">
                      <div className="flex-1 overflow-y-auto p-2">
                        <RoomRoster
                          participants={galleryParticipants}
                          videoRefFor={participantVideos.refFor}
                          localParticipantId={localParticipantId}
                          className="flex flex-col gap-2"
                        />
                      </div>
                      {/* 미니 창 컨트롤: 마이크·카메라·공유중지·나가기. onClick 은 portal 이라도 React 트리로 전달돼 동작한다. */}
                      <div className="flex shrink-0 items-center justify-center gap-2 border-t border-room-line bg-[#0e1020] p-2">
                        <button
                          type="button"
                          onClick={media.toggleMicrophone}
                          disabled={!media.ready || media.microphoneBlocked}
                          title={media.microphoneEnabled ? "마이크 끄기" : "마이크 켜기"}
                          aria-label={media.microphoneEnabled ? "마이크 끄기" : "마이크 켜기"}
                          className={`${pipBtn} ${media.microphoneEnabled ? "bg-room-control" : "bg-danger"}`}
                        >
                          <MicIcon />
                        </button>
                        <button
                          type="button"
                          onClick={media.toggleCamera}
                          disabled={!media.ready || media.cameraBlocked}
                          title={media.cameraEnabled ? "카메라 끄기" : "카메라 켜기"}
                          aria-label={media.cameraEnabled ? "카메라 끄기" : "카메라 켜기"}
                          className={`${pipBtn} ${media.cameraEnabled ? "bg-room-control" : "bg-danger"}`}
                        >
                          <CameraIcon />
                        </button>
                        {isSharing && (
                          <button
                            type="button"
                            onClick={toggleScreenShare}
                            title="화면 공유 중지"
                            aria-label="화면 공유 중지"
                            className={`${pipBtn} bg-primary`}
                          >
                            <ScreenShareIcon />
                          </button>
                        )}
                        <button
                          type="button"
                          onClick={leaveRoom}
                          title="나가기"
                          aria-label="나가기"
                          className={`${pipBtn} bg-danger`}
                        >
                          <CloseIcon />
                        </button>
                      </div>
                    </div>,
                    pipWindow.document.body,
                  )}
                {isSharing && (
                  <div className="absolute bottom-4 left-1/2 z-[2] -translate-x-1/2">
                    <button
                      type="button"
                      onClick={toggleScreenShare}
                      className="z-btn z-btn-danger rounded-full px-5 py-[11px] text-[13.5px]"
                    >
                      화면 공유 중지
                    </button>
                  </div>
                )}
              </div>
            ) : view === "gallery" ? (
              <ParticipantGrid
                participants={galleryParticipants}
                currentParticipantId={localParticipantId ?? undefined}
                videoRefFor={participantVideos.refFor}
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
                {/*
                  강사 카메라. 아바타 뒤에 두어 영상이 위에 그려지고, 카메라가 꺼져 있으면 감춰 아바타가 보이게 한다.
                  요소를 항상 마운트해 둬야 트랙 부착 훅이 언제 동기화해도 붙는다(갤러리 타일과 같은 이유).
                  내 화면일 때만 거울처럼 뒤집는다.
                */}
                {host !== undefined && (
                  <video
                    ref={participantVideos.refFor(host.id)}
                    autoPlay
                    muted
                    playsInline
                    data-testid="speaker-video"
                    className={`absolute inset-0 size-full object-contain ${
                      host.id === localParticipantId ? "scale-x-[-1]" : ""
                    } ${host.cameraEnabled ? "" : "invisible"}`}
                  />
                )}
                {/* 강사 이름은 LiveKit 참가자 목록에서 온다. 아직 없을 때 칩을 그리면 "강의:  선생님" 처럼 빈칸이 남는다. */}
                <div className="pointer-events-none absolute inset-0">
                  {hostName === "" ? (
                    <div className="z-stage-chip absolute left-4 top-4 font-bold">
                      강의자를 기다리고 있어요
                    </div>
                  ) : (
                    <>
                      <div className="z-stage-chip absolute left-4 top-4 font-bold">
                        강의: {hostName} 선생님
                      </div>
                      <div className="z-stage-chip absolute bottom-4 left-4 font-bold">
                        📶 {hostName} 선생님
                      </div>
                    </>
                  )}
                </div>
              </>
            )}

            {/*
              카메라 꺼짐 안내는 카메라 안내 프롬프트(81)가 원인별로 맡는다. 여기 있던 프로토타입
              배너는 "10분 이상·이후 5분마다"라는 옛 규칙이라 확정된 1분 발동·5분 재권유와
              어긋나고 문구도 겹쳐 제거했다(76: 중복 구현하지 않는다).
            */}

            {/*
              수업 팁 (강사). 문구는 서버가 §8 템플릿으로 완성해 내려주므로 그대로 표시한다.
              여기 있던 프로토타입 카드는 "학생 30%에게서 신호가 나타났어요" 라는 고정 문구라
              실제 집계와 무관했다(86).
            */}
            {isConfirmedInstructor && coachTip.tip !== null && (
              <CoachTipCard tip={coachTip.tip} onDismiss={coachTip.dismiss} />
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
            sharing={isSharing}
            shareBlocked={shareBlocked}
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
            onToggleShare={toggleScreenShare}
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
            messages={chat.messages}
            meId={meId ?? ""}
            isInstructor={isInstructor}
            canSendChat={chat.canSend}
            onSendChat={chat.send}
            onRetryChat={chat.retry}
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

      {/* 나가기 확인 말풍선(강사 전용). 종료는 모든 참가자가 나가는 되돌릴 수 없는 조작이라 한 번 더 묻는다. */}
      {leaveConfirming && (
        <div
          role="group"
          aria-label="수업 종료 확인"
          data-testid="leave-confirm-bubble"
          className="absolute bottom-24 left-1/2 z-50 flex -translate-x-1/2 animate-[zPop_.18s] items-center gap-2.5 rounded-[14px] border border-danger bg-danger-softer px-4 py-2.5 shadow-pop"
        >
          <span className="whitespace-nowrap text-[13px] font-bold text-danger">
            {endSessionError ?? "수업을 종료할까요? 모든 참가자가 나가게 됩니다."}
          </span>
          <button
            type="button"
            data-testid="leave-confirm-end"
            disabled={endingSession}
            onClick={() => void confirmEndSession()}
            className="cursor-pointer whitespace-nowrap rounded-lg bg-danger px-3 py-1 text-[12.5px] font-bold text-surface disabled:cursor-not-allowed disabled:opacity-60"
          >
            {endingSession ? "종료 중" : "종료"}
          </button>
          <button
            type="button"
            data-testid="leave-confirm-cancel"
            disabled={endingSession}
            onClick={() => setLeaveConfirming(false)}
            className="cursor-pointer whitespace-nowrap rounded-lg border border-line-muted bg-surface px-3 py-1 text-[12.5px] font-bold text-ink-sub disabled:cursor-not-allowed disabled:opacity-60"
          >
            취소
          </button>
        </div>
      )}
    </div>
  );
}
