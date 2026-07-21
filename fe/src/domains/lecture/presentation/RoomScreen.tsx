"use client";

import { useState } from "react";
import Link from "next/link";
import { color } from "@/shared/lib/theme";
import { DistributionBar } from "@/shared/ui";
import {
  alertDistribution,
  dmMessages,
  participants as participantsFixture,
  publicMessages,
} from "./fixtures";
import { RoomTile } from "./components/room/RoomTile";
import { RoomControlBar } from "./components/room/RoomControlBar";
import { RoomSidePanel } from "./components/room/RoomSidePanel";

/**
 * SC-09 실시간 강의실 (밝은 테마). 갤러리/발표자 보기 · 컨트롤 바 · 사이드 패널 ·
 * 확인 프롬프트/집단 알림 모달을 구성한다. 미디어·실시간 연결은 붙이지 않았고
 * 역할·보기·패널·모달 등 화면 상태만 로컬로 동작한다.
 */
export function RoomScreen({ roomTitle = "React 상태관리 심화" }: { roomTitle?: string }) {
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
  const count = list.length;
  const gallery = list.slice(0, 12);
  const messages = chatTab === "public" ? publicMessages : dmMessages;
  const meCamOff = !list.find((p) => p.id === meId)?.cam;
  const hostName = "박서준";

  const toggleMe = (k: "mic" | "cam" | "hand") => setMe((p) => ({ ...p, [k]: !p[k] }));
  const onPreview = () => (isInstructor ? setAlertOpen(true) : setPromptOpen(true));

  return (
    <div style={{ height: "100vh", display: "flex", flexDirection: "column", background: color.bgMintDeep, color: color.text }}>
      {/* 상단 바 */}
      <div
        style={{
          position: "relative",
          display: "flex",
          alignItems: "center",
          gap: 16,
          padding: "13px 24px",
          background: "#fff",
          borderBottom: `1px solid ${color.border}`,
          flexShrink: 0,
        }}
      >
        <div style={{ fontWeight: 900, fontSize: 20, color: color.primary, letterSpacing: "-.5px" }}>ZANI</div>
        <div style={{ fontWeight: 800, fontSize: 14.5, color: color.text }}>{roomTitle}</div>
        <div style={{ display: "flex", alignItems: "center", gap: 9, paddingLeft: 6, borderLeft: `1px solid #e3eeea` }}>
          <span style={{ display: "inline-flex", alignItems: "center", gap: 5, color: color.red, fontWeight: 800, fontSize: 12.5 }}>
            <span style={{ width: 7, height: 7, borderRadius: "50%", background: color.red, animation: "zPulse 1.4s infinite" }} />
            LIVE
          </span>
          <span style={{ fontFamily: "var(--font-space-mono), monospace", color: color.textMuted, fontSize: 13 }}>00:12:04</span>
        </div>
        <div
          style={{
            position: "absolute",
            left: "50%",
            transform: "translateX(-50%)",
            display: "inline-flex",
            alignItems: "center",
            gap: 8,
            background: color.primarySoft,
            border: "1px solid #c6eedf",
            color: color.primary,
            padding: "8px 17px",
            borderRadius: 999,
            fontSize: 13,
            fontWeight: 800,
          }}
        >
          ⧉ 집중 분석 중 📊
        </div>
        <div style={{ flex: 1 }} />
        <span style={{ display: "inline-flex", alignItems: "center", gap: 6, fontSize: 13.5, color: color.textSub, fontWeight: 700 }}>
          👥 참여자 {count}명
        </span>
        <button onClick={() => setRole(isInstructor ? "student" : "instructor")} title="역할 전환 (미리보기)" style={rolePill(isInstructor)}>
          {isInstructor ? "강사" : "학생"}
        </button>
        <Link href="/home" style={{ padding: "9px 18px", borderRadius: 11, border: "none", background: color.red, color: "#fff", fontWeight: 800, fontSize: 13.5, textDecoration: "none" }}>
          나가기
        </Link>
      </div>

      {/* 본문 */}
      <div style={{ flex: 1, display: "flex", minHeight: 0, padding: 14, gap: 14 }}>
        {/* 좌측 컬럼 */}
        <div style={{ flex: 1, minWidth: 0, display: "flex", flexDirection: "column", gap: 14 }}>
          {/* 스테이지 */}
          <div style={{ flex: 1, minHeight: 0, position: "relative", background: color.ink, borderRadius: 18, overflow: "hidden" }}>
            <div style={stageChip}>⊞ 참여자 전체 보기 {count}명</div>
            <div style={{ position: "absolute", right: 16, top: 16, zIndex: 4, display: "flex", gap: 8 }}>
              <button onClick={() => setView(view === "gallery" ? "speaker" : "gallery")} style={{ ...stageChipBtn }}>
                ⊞ {view === "gallery" ? "발표자 보기" : "갤러리 보기"}
              </button>
            </div>

            {view === "gallery" ? (
              <div style={{ height: "100%", overflowY: "auto", padding: "60px 16px 16px", display: "grid", gridTemplateColumns: "repeat(6,1fr)", gap: 10, alignContent: "start" }}>
                {gallery.map((p) => (
                  <RoomTile key={p.id} participant={p} canControl={isInstructor && !p.host && p.id !== meId} />
                ))}
              </div>
            ) : (
              <>
                <div
                  style={{
                    position: "absolute",
                    inset: 0,
                    background: "repeating-linear-gradient(135deg,#12142a,#12142a 20px,#171a34 20px,#171a34 40px)",
                    display: "flex",
                    alignItems: "center",
                    justifyContent: "center",
                    flexDirection: "column",
                    gap: 10,
                  }}
                >
                  <div style={{ fontSize: 44 }}>🖥️</div>
                  <div style={{ color: "#e7e9fb", fontWeight: 800, fontSize: 16 }}>강의: {hostName} 선생님</div>
                  <div style={{ color: "#8a90b8", fontSize: 13, fontFamily: "var(--font-space-mono), monospace" }}>발표자 화면</div>
                </div>
                <div style={{ position: "absolute", left: 16, bottom: 16, display: "inline-flex", alignItems: "center", gap: 7, background: "#0e1020cc", color: "#e7e9fb", fontSize: 12.5, fontWeight: 700, padding: "8px 13px", borderRadius: 10, backdropFilter: "blur(6px)" }}>
                  📶 {hostName} 선생님
                </div>
              </>
            )}

            {/* 학생 분석 상태 */}
            {!isInstructor && (
              <div style={{ position: "absolute", left: 16, top: 62, zIndex: 4, display: "flex", alignItems: "center", gap: 9, background: "#1e2138cc", border: "1px solid #34395a", borderRadius: 999, padding: "7px 14px", backdropFilter: "blur(8px)" }}>
                <span style={{ width: 8, height: 8, borderRadius: "50%", background: color.primary, animation: "zPulse 1.5s infinite" }} />
                <span style={{ fontSize: 12.5, color: "#cfd3f0", fontWeight: 700 }}>학습 신호 분석 중</span>
                <span style={{ fontSize: 11.5, color: "#8a90b8" }}>· 원본 영상은 저장되지 않아요</span>
              </div>
            )}

            {/* 카메라 꺼짐 안내 */}
            {!isInstructor && meCamOff && (
              <div style={{ position: "absolute", left: "50%", top: 18, transform: "translateX(-50%)", zIndex: 5, background: "#fdf6df", border: "1px solid #f3dc90", color: "#836607", borderRadius: 14, padding: "11px 18px", fontSize: 13, fontWeight: 700, boxShadow: "0 8px 24px #0004", animation: "zPop .2s" }}>
                📷 카메라가 꺼져 있어요. 켜면 학습 신호 분석에 참여할 수 있어요.
              </div>
            )}

            {/* 집단 알림 (강사) */}
            {isInstructor && alertOpen && (
              <div style={{ position: "absolute", right: 16, top: 62, zIndex: 5, width: 290, background: "#fff", color: color.text, borderRadius: 18, padding: 18, boxShadow: "0 16px 44px #0006", animation: "zPop .2s" }}>
                <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", marginBottom: 8 }}>
                  <span style={{ display: "inline-flex", alignItems: "center", gap: 6, fontWeight: 800, color: color.amberText, fontSize: 13, background: "#fdf6df", padding: "4px 10px", borderRadius: 8 }}>
                    ⚠ 개념 확인 필요
                  </span>
                  <button onClick={() => setAlertOpen(false)} style={{ border: "none", background: "none", color: "#b7bcd8", cursor: "pointer", fontSize: 16 }}>✕</button>
                </div>
                <p style={{ margin: "0 0 12px", fontSize: 13.5, lineHeight: 1.55, color: color.textLabel }}>
                  최근 5분간 <b style={{ color: color.amberText }}>확인 필요 32%</b> — 접속 학생 <b>24명 중 8명</b>에게서 신호가 나타났어요.
                </p>
                <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
                  {alertDistribution.map((d) => (
                    <DistributionBar key={d.label} label={d.label} percent={d.percent} fill={d.color} value={d.value} labelWidth={58} />
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
            onPreview={onPreview}
          />
        </div>

        {/* 사이드 패널 (발표자 보기) */}
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
        <div style={{ position: "absolute", left: "50%", bottom: 96, transform: "translateX(-50%)", width: 420, background: "#fff", color: color.text, borderRadius: 20, padding: 22, boxShadow: "0 20px 50px #0008", animation: "zPop .2s", zIndex: 50 }}>
          <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", marginBottom: 6 }}>
            <span style={{ fontWeight: 800, fontSize: 16 }}>잠깐 확인할게요 ✋</span>
            <span style={{ minWidth: 34, height: 34, padding: "0 8px", borderRadius: 10, display: "flex", alignItems: "center", justifyContent: "center", fontWeight: 900, fontFamily: "var(--font-space-mono), monospace", fontSize: 15, background: color.primarySoft, color: color.primary }}>
              30
            </span>
          </div>
          <p style={{ margin: "0 0 16px", color: color.textSub, fontSize: 14 }}>
            방금 설명한 내용, 지금 어떤가요? 응답은 강사에게 개인별로 공개되지 않아요.
          </p>
          <div style={{ display: "flex", gap: 10 }}>
            <button onClick={() => setPromptOpen(false)} style={promptBtn("#ebf8f3", "#19c986", "#d4f0e5")}>👍 이해했어요</button>
            <button onClick={() => setPromptOpen(false)} style={promptBtn("#fdf6df", "#b78f0c", "#f6e3a7")}>🤔 헷갈려요</button>
            <button onClick={() => setPromptOpen(false)} style={promptBtn("#f0f8f5", "#6a7096", "#dfebe7")}>😅 놓쳤어요</button>
          </div>
        </div>
      )}
    </div>
  );
}

function rolePill(isInstructor: boolean) {
  return {
    padding: "5px 13px",
    borderRadius: 999,
    fontSize: 12,
    fontWeight: 800,
    cursor: "pointer",
    fontFamily: "inherit",
    background: isInstructor ? color.primarySoft : "#eaf3ff",
    color: isInstructor ? color.primaryDeep : "#4a6fd6",
    border: `1px solid ${isInstructor ? "#c6eedf" : "#cfe0f7"}`,
  } as const;
}

const stageChip = {
  position: "absolute" as const,
  left: 16,
  top: 16,
  zIndex: 4,
  display: "inline-flex",
  alignItems: "center",
  gap: 7,
  background: "#0e1020cc",
  color: "#e7e9fb",
  fontSize: 12.5,
  fontWeight: 800,
  padding: "8px 13px",
  borderRadius: 10,
  backdropFilter: "blur(6px)",
} as const;

const stageChipBtn = {
  display: "inline-flex",
  alignItems: "center",
  gap: 7,
  background: "#0e1020cc",
  color: "#e7e9fb",
  fontSize: 12.5,
  fontWeight: 800,
  padding: "8px 13px",
  borderRadius: 10,
  border: "none",
  cursor: "pointer",
  backdropFilter: "blur(6px)",
  fontFamily: "inherit",
} as const;

function promptBtn(bg: string, fg: string, border: string) {
  return {
    flex: 1,
    padding: 14,
    borderRadius: 14,
    border: `1.5px solid ${border}`,
    background: bg,
    color: fg,
    fontWeight: 800,
    cursor: "pointer",
    fontFamily: "inherit",
  } as const;
}
