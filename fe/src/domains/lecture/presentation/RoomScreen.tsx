"use client";

import { useState } from "react";
import Link from "next/link";
import { DistributionBar } from "@/shared/ui";
import {
  alertDistribution,
  dmMessages,
  participants as participantsFixture,
  publicMessages,
} from "./fixtures";
import { ParticipantGrid } from "./components/room/ParticipantGrid";
import { useRoomParticipants } from "./useRoomParticipants";
import { RoomControlBar } from "./components/room/RoomControlBar";
import { RoomSidePanel } from "./components/room/RoomSidePanel";
import { SessionTimeWarning } from "./components/room/SessionTimeWarning";
import { EndSessionButton } from "./components/room/EndSessionButton";
import { RoomProvider, useRoomConnection } from "./RoomProvider";

/**
 * SC-09 실시간 강의실 (밝은 테마). LiveKit room connection is attached here;
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

export function RoomScreen({ sessionId, roomTitle, expiresAt }: RoomScreenProps) {
  return (
    <RoomProvider sessionId={sessionId}>
      <RoomScreenContent sessionId={sessionId} roomTitle={roomTitle} expiresAt={expiresAt} />
    </RoomProvider>
  );
}

function RoomScreenContent({
  sessionId,
  roomTitle = "React 상태관리 심화",
  expiresAt,
}: Pick<RoomScreenProps, "sessionId" | "roomTitle" | "expiresAt">) {
  const { connectionState, retry, sessionExpiresAt } = useRoomConnection();
  const { participants: tileParticipants, localParticipantId } = useRoomParticipants();
  const [role, setRole] = useState<"instructor" | "student">("instructor");
  const [view, setView] = useState<"gallery" | "speaker">("gallery");
  const [panel, setPanel] = useState<"people" | "chat">("people");
  const [chatTab, setChatTab] = useState<"public" | "dm">("public");
  const [me, setMe] = useState({ mic: true, cam: true, hand: false });
  const [reactMenuOpen, setReactMenuOpen] = useState(false);
  const [sharing, setSharing] = useState(false);
  const [promptOpen, setPromptOpen] = useState(false);
  const [alertOpen, setAlertOpen] = useState(false);

  const isInstructor = role === "instructor";
  const meId = isInstructor ? "p0" : "p7";
  const list = participantsFixture.map((p) => (p.id === meId ? { ...p, ...me } : p));
  // 갤러리 그리드와 참여자 수는 실제 room 참가자(useRoomParticipants)를 사용한다.
  // 사이드 패널 people/chat, 하단 제어바는 아직 fixture 기반(각각 WebSocket·57 소관).
  const count = tileParticipants.length;
  const messages = chatTab === "public" ? publicMessages : dmMessages;
  const meCamOff = !list.find((p) => p.id === meId)?.cam;
  const hostName = "박서준";
  const connectionLabel =
    connectionState === "connecting"
      ? "연결 중"
      : connectionState === "connected"
        ? "LIVE"
        : "연결 실패";

  const toggleMe = (k: "mic" | "cam" | "hand") => setMe((p) => ({ ...p, [k]: !p[k] }));

  return (
    <div className="flex h-screen flex-col bg-mint-deep text-ink">
      {/* 최대 수업 시간 종료 임박 안내(서버 자동 종료와 짝) */}
      <SessionTimeWarning expiresAt={expiresAt ?? sessionExpiresAt ?? undefined} />
      {/* 상단 바 */}
      <div className="relative flex shrink-0 items-center gap-4 border-b border-line bg-surface px-6 py-[13px]">
        <div className="text-xl font-black tracking-[-.5px] text-primary">ZANI</div>
        <div className="text-[14.5px] font-extrabold">{roomTitle}</div>
        <div className="flex items-center gap-[9px] border-l border-line-soft pl-1.5">
          <span
            role="status"
            aria-live="polite"
            className="inline-flex items-center gap-[5px] text-[12.5px] font-extrabold text-danger"
          >
            <span className="size-[7px] animate-[zPulse_1.4s_infinite] rounded-full bg-danger" />
            {connectionLabel}
          </span>
          {connectionState === "error" && (
            <div
              role="alert"
              className="absolute left-1/2 top-full z-10 mt-2 flex -translate-x-1/2 items-center gap-3 rounded-xl border border-danger bg-surface px-4 py-2 text-[13px] text-ink shadow-lg"
            >
              <span>실시간 강의 연결에 실패했습니다.</span>
              <button
                type="button"
                onClick={retry}
                className="cursor-pointer rounded-lg bg-danger px-3 py-1 font-bold text-surface"
              >
                다시 연결
              </button>
            </div>
          )}
          <span className="font-mono text-[13px] text-ink-muted">00:12:04</span>
        </div>

        <div className="absolute left-1/2 inline-flex -translate-x-1/2 items-center gap-2 rounded-full border border-line-primary bg-primary-soft px-[17px] py-2 text-[13px] font-extrabold text-primary">
          ⧉ 집중 분석 중 📊
        </div>

        <div className="flex-1" />
        <span className="inline-flex items-center gap-1.5 text-[13.5px] font-bold text-ink-sub">
          👥 참여자 {count}명
        </span>
        <button
          onClick={() => setRole(isInstructor ? "student" : "instructor")}
          title="역할 전환 (미리보기)"
          className={`cursor-pointer rounded-full border px-[13px] py-[5px] font-sans text-xs font-extrabold ${
            isInstructor
              ? "border-line-primary bg-primary-soft text-primary-deep"
              : "border-[#cfe0f7] bg-[#eaf3ff] text-info"
          }`}
        >
          {isInstructor ? "강사" : "학생"}
        </button>
        {isInstructor && <EndSessionButton sessionId={sessionId} />}
        <Link
          href="/home"
          className="z-btn rounded-[11px] border border-line-muted bg-surface px-[18px] py-[9px] text-[13.5px] text-ink-sub"
        >
          나가기
        </Link>
      </div>

      {/* 본문 */}
      <div className="flex min-h-0 flex-1 gap-3.5 p-3.5">
        <div className="flex min-w-0 flex-1 flex-col gap-3.5">
          {/* 스테이지 */}
          <div className="relative min-h-0 flex-1 overflow-hidden rounded-[18px] bg-stage">
            <div className="absolute left-4 top-4 z-[4] z-stage-chip">
              ⊞ 참여자 전체 보기 {count}명
            </div>
            <div className="absolute right-4 top-4 z-[4] flex gap-2">
              <button
                onClick={() => setView(view === "gallery" ? "speaker" : "gallery")}
                className="z-stage-chip cursor-pointer border-0 font-sans"
              >
                ⊞ {view === "gallery" ? "발표자 보기" : "갤러리 보기"}
              </button>
            </div>

            {view === "gallery" ? (
              <ParticipantGrid
                participants={tileParticipants}
                currentParticipantId={localParticipantId ?? undefined}
                isInstructor={isInstructor}
              />
            ) : (
              <>
                <div className="absolute inset-0 flex flex-col items-center justify-center gap-2.5 [background:repeating-linear-gradient(135deg,#12142a,#12142a_20px,#171a34_20px,#171a34_40px)]">
                  <div className="text-[44px]">🖥️</div>
                  <div className="text-base font-extrabold text-[#e7e9fb]">
                    강의: {hostName} 선생님
                  </div>
                  <div className="font-mono text-[13px] text-panel-muted">발표자 화면</div>
                </div>
                <div className="absolute bottom-4 left-4 z-stage-chip font-bold">
                  📶 {hostName} 선생님
                </div>
              </>
            )}

            {/* 학생 분석 상태 */}
            {!isInstructor && (
              <div className="absolute left-4 top-[62px] z-[4] flex items-center gap-[9px] rounded-full border border-room-edge bg-[#1e2138cc] px-3.5 py-[7px] backdrop-blur-lg">
                <span className="size-2 animate-[zPulse_1.5s_infinite] rounded-full bg-primary" />
                <span className="text-[12.5px] font-bold text-panel-soft">학습 신호 분석 중</span>
                <span className="text-[11.5px] text-panel-muted">· 원본 영상은 저장되지 않아요</span>
              </div>
            )}

            {/* 카메라 꺼짐 안내 */}
            {!isInstructor && meCamOff && (
              <div className="absolute left-1/2 top-[18px] z-[5] -translate-x-1/2 animate-[zPop_.2s] rounded-[14px] border border-[#f3dc90] bg-warn-soft px-[18px] py-[11px] text-[13px] font-bold text-[#836607] shadow-[0_8px_24px_#0004]">
                📷 카메라가 꺼져 있어요. 켜면 학습 신호 분석에 참여할 수 있어요.
              </div>
            )}

            {/* 집단 알림 (강사) */}
            {isInstructor && alertOpen && (
              <div className="absolute right-4 top-[62px] z-[5] w-[290px] animate-[zPop_.2s] rounded-[18px] bg-surface p-[18px] text-ink shadow-[0_16px_44px_#0006]">
                <div className="mb-2 flex items-center justify-between">
                  <span className="z-pill bg-warn-soft px-2.5 py-1 text-[13px] text-warn-text">
                    ⚠ 개념 확인 필요
                  </span>
                  <button
                    onClick={() => setAlertOpen(false)}
                    className="cursor-pointer border-0 bg-transparent text-base text-ink-quiet"
                  >
                    ✕
                  </button>
                </div>
                <p className="mb-3 text-[13.5px] leading-[1.55] text-ink-label">
                  최근 5분간 <b className="text-warn-text">확인 필요 32%</b> — 접속 학생{" "}
                  <b>24명 중 8명</b>에게서 신호가 나타났어요.
                </p>
                <div className="flex flex-col gap-2">
                  {alertDistribution.map((d) => (
                    <DistributionBar
                      key={d.label}
                      label={d.label}
                      percent={d.percent}
                      fill={d.color}
                      value={d.value}
                      labelWidth={58}
                    />
                  ))}
                </div>
              </div>
            )}
          </div>

          <RoomControlBar
            isInstructor={isInstructor}
            me={me}
            sharing={sharing}
            reactMenuOpen={reactMenuOpen}
            onToggleMic={() => toggleMe("mic")}
            onToggleCam={() => toggleMe("cam")}
            onToggleShare={() => setSharing((v) => !v)}
            onToggleHand={() => toggleMe("hand")}
            onToggleReactMenu={() => setReactMenuOpen((v) => !v)}
            onPreview={() => (isInstructor ? setAlertOpen(true) : setPromptOpen(true))}
          />
        </div>

        {view === "speaker" && (
          <RoomSidePanel
            panel={panel}
            onPanel={setPanel}
            chatTab={chatTab}
            onChatTab={setChatTab}
            participants={list}
            messages={messages}
            meId={meId}
            isInstructor={isInstructor}
          />
        )}
      </div>

      {/* 확인 프롬프트 모달 (학생) */}
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
              onClick={() => setPromptOpen(false)}
              className="z-btn flex-1 rounded-[14px] border-[1.5px] border-[#d4f0e5] bg-primary-mint py-3.5 text-primary-dark"
            >
              👍 이해했어요
            </button>
            <button
              onClick={() => setPromptOpen(false)}
              className="z-btn flex-1 rounded-[14px] border-[1.5px] border-[#f6e3a7] bg-warn-soft py-3.5 text-warn-text"
            >
              🤔 헷갈려요
            </button>
            <button
              onClick={() => setPromptOpen(false)}
              className="z-btn flex-1 rounded-[14px] border-[1.5px] border-line-muted bg-primary-softer py-3.5 text-ink-muted"
            >
              😅 놓쳤어요
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
