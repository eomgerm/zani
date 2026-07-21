"use client";

import { useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { color } from "@/shared/lib/theme";

const MIC_BARS = Array.from({ length: 14 }, (_, i) => ({ duration: 0.6 + (i % 5) * 0.12, delay: i * 0.05 }));

/**
 * SC-08 입장 전 점검. 얼굴 위치·움직임 확인 단계를 거쳐 강의실로 입장한다.
 * 실제 카메라 대신 단계 스텝을 로컬 상태로 시연한다.
 */
export function PrejoinScreen({ inviteCode }: { inviteCode: string }) {
  const router = useRouter();
  const [step, setStep] = useState<1 | 2 | 3>(1);

  const next = () => setStep((s) => (s < 3 ? ((s + 1) as 2 | 3) : s));

  return (
    <div
      style={{
        minHeight: "100vh",
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        padding: 28,
        background: color.bgMint,
      }}
    >
      <div style={{ width: "100%", maxWidth: 1200 }}>
        <div style={{ display: "flex", alignItems: "center", gap: 12, marginBottom: 20 }}>
          <Link href="/home" style={backBtn}>
            ←
          </Link>
          <div>
            <div style={{ fontWeight: 800, fontSize: 22, letterSpacing: "-.4px" }}>입장 전 점검</div>
            <div style={{ color: color.textFaint, fontSize: 13.5, marginTop: 2 }}>
              초대 코드 <b style={{ color: color.primary }}>{inviteCode}</b> · 카메라와 마이크를 확인해 주세요
            </div>
          </div>
        </div>

        <div style={{ display: "grid", gridTemplateColumns: "1.55fr 1fr", gap: 22, alignItems: "stretch" }}>
          {/* 카메라 프리뷰 */}
          <div style={{ position: "relative", borderRadius: 22, overflow: "hidden", background: "#1a1d30", minHeight: 540 }}>
            <div
              style={{
                position: "absolute",
                inset: 0,
                background: "repeating-linear-gradient(135deg,#1e2138,#1e2138 16px,#232744 16px,#232744 32px)",
              }}
            />
            <div style={{ position: "absolute", inset: 0 }}>
              {/* 상단 좌 */}
              <div style={{ position: "absolute", left: 18, top: 18, display: "flex", alignItems: "center", gap: 10 }}>
                {step < 3 ? (
                  <>
                    <span style={{ background: color.primary, color: "#fff", fontWeight: 800, fontSize: 12.5, padding: "5px 11px", borderRadius: 9 }}>
                      {step} / 2
                    </span>
                    <span style={{ color: "#fff", fontWeight: 800, fontSize: 14, textShadow: "0 1px 6px #0007" }}>
                      {step === 1 ? "얼굴 위치 맞추기" : "움직임 확인"}
                    </span>
                  </>
                ) : (
                  <>
                    <span style={{ display: "inline-flex", alignItems: "center", gap: 5, background: "#ebf8f3", color: "#19c986", fontWeight: 800, fontSize: 12.5, padding: "5px 11px", borderRadius: 9 }}>
                      ✓ 완료
                    </span>
                    <span style={{ color: "#fff", fontWeight: 800, fontSize: 14, textShadow: "0 1px 6px #0007" }}>점검 완료</span>
                  </>
                )}
              </div>
              {/* 상단 우 */}
              <div style={{ position: "absolute", right: 18, top: 18 }}>
                <span style={{ display: "inline-flex", alignItems: "center", gap: 7, background: "#0009", color: "#e7e9fb", fontWeight: 800, fontSize: 12.5, padding: "7px 13px", borderRadius: 10, backdropFilter: "blur(6px)" }}>
                  {step === 1 ? "⧉ 인식 준비 중" : step === 2 ? "◌ 움직임 분석 중" : "✓ 확인 완료"}
                </span>
              </div>
              {/* 중앙 */}
              <div
                style={{
                  position: "absolute",
                  inset: 0,
                  display: "flex",
                  flexDirection: "column",
                  alignItems: "center",
                  justifyContent: "center",
                  textAlign: "center",
                  padding: "0 30px",
                }}
              >
                {step === 3 ? (
                  <>
                    <div
                      style={{
                        width: 96,
                        height: 96,
                        borderRadius: "50%",
                        background: "#41cb96",
                        display: "flex",
                        alignItems: "center",
                        justifyContent: "center",
                        color: "#fff",
                        fontSize: 46,
                        boxShadow: "0 0 0 10px #2fb57238,0 0 40px #2fb57266",
                        marginBottom: 20,
                      }}
                    >
                      ✓
                    </div>
                    <div style={{ color: "#fff", fontSize: 26, fontWeight: 800, textShadow: "0 2px 10px #0008" }}>
                      점검이 완료되었어요
                    </div>
                  </>
                ) : (
                  <>
                    <FaceFrame moving={step === 2} />
                    <div style={{ color: "#fff", fontSize: 24, fontWeight: 800, textShadow: "0 2px 10px #0008", marginBottom: 8 }}>
                      {step === 1 ? "얼굴을 프레임 안에 맞춰 주세요" : "고개를 천천히 좌우로 움직여 주세요"}
                    </div>
                    <div style={{ color: "#ffffffdd", fontSize: 14.5, lineHeight: 1.55, textShadow: "0 1px 8px #0009" }}>
                      {step === 1
                        ? "얼굴과 어깨가 프레임 안에 보이도록 위치를 조정해 주세요"
                        : "얼굴 각도와 움직임이 잘 인식되는지 확인하고 있어요"}
                    </div>
                  </>
                )}
              </div>
              {/* 카메라 칩 */}
              <div style={{ position: "absolute", left: 18, bottom: 18, display: "inline-flex", alignItems: "center", gap: 7, background: "#0009", color: "#e7e9fb", fontSize: 12.5, fontWeight: 700, padding: "8px 13px", borderRadius: 10, backdropFilter: "blur(6px)" }}>
                🎥 카메라 · 720p
              </div>
            </div>
          </div>

          {/* 우측 */}
          <div style={{ display: "flex", flexDirection: "column", gap: 16 }}>
            <div style={cardBox}>
              <div style={{ fontWeight: 800, fontSize: 16, marginBottom: 15 }}>장치 확인</div>
              <div style={{ display: "flex", flexDirection: "column", gap: 13 }}>
                {["브라우저 · Chrome", "카메라 · 720p 로지텍", "마이크 입력 레벨"].map((t) => (
                  <div key={t} style={{ display: "flex", alignItems: "center", gap: 11 }}>
                    <span style={checkMark}>✓</span>
                    <span style={{ flex: 1, fontSize: 14, fontWeight: 600 }}>{t}</span>
                  </div>
                ))}
                <div style={{ display: "flex", gap: 3, alignItems: "flex-end", height: 26, paddingLeft: 35 }}>
                  {MIC_BARS.map((b, i) => (
                    <span
                      key={i}
                      style={{ width: 6, borderRadius: 3, background: color.primary, animation: `zLevel ${b.duration}s ease-in-out ${b.delay}s infinite` }}
                    />
                  ))}
                </div>
                {step === 3 && (
                  <div style={{ display: "flex", alignItems: "center", gap: 11 }}>
                    <span style={checkMark}>✓</span>
                    <span style={{ flex: 1, fontSize: 14, fontWeight: 600 }}>얼굴 인식 및 움직임 확인 완료</span>
                  </div>
                )}
              </div>
            </div>

            <div style={{ ...cardBox, display: "flex", flexDirection: "column", gap: 8 }}>
              <div style={{ fontSize: 13, color: color.textFaint, fontWeight: 700 }}>카메라</div>
              <div style={deviceRow}>Logitech C920 HD</div>
              <div style={{ fontSize: 13, color: color.textFaint, fontWeight: 700, marginTop: 4 }}>마이크</div>
              <div style={deviceRow}>기본 - 내장 마이크</div>
            </div>

            <div style={{ background: color.bg, border: `1px solid ${color.borderMint}`, borderRadius: 20, padding: "18px 20px", display: "flex", gap: 13 }}>
              <span style={{ flexShrink: 0, color: color.primary, fontSize: 20 }}>⧉</span>
              <div>
                <div style={{ fontWeight: 800, fontSize: 13.5, marginBottom: 5 }}>이렇게 활용돼요</div>
                <p style={{ margin: 0, color: color.textFaint, fontSize: 12.5, lineHeight: 1.6 }}>
                  카메라는 수업 중 표정, 시선, 고개 움직임 등을 분석해 이해도와 참여도를 파악하는 데 사용돼요. 분석 결과는{" "}
                  <span style={{ color: color.primary, fontWeight: 700 }}>본인에게만</span> 제공되며 안전하게 보호됩니다.
                </p>
              </div>
            </div>

            <div style={{ flex: 1 }} />
            <div style={{ display: "flex", gap: 12 }}>
              {step < 3 ? (
                <button onClick={next} style={enterBtn}>
                  다음 단계
                </button>
              ) : (
                <button onClick={() => router.push(`/room/${inviteCode}`)} style={enterBtn}>
                  수업 입장하기
                </button>
              )}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}

function FaceFrame({ moving }: { moving: boolean }) {
  const corner = { position: "absolute" as const, width: 40, height: 40 };
  return (
    <div style={{ position: "relative", width: 230, height: 270, marginBottom: 22 }}>
      <span style={{ ...corner, top: 0, left: 0, borderTop: "3px solid #fff", borderLeft: "3px solid #fff", borderTopLeftRadius: 16 }} />
      <span style={{ ...corner, top: 0, right: 0, borderTop: "3px solid #fff", borderRight: "3px solid #fff", borderTopRightRadius: 16 }} />
      <span style={{ ...corner, bottom: 0, left: 0, borderBottom: "3px solid #fff", borderLeft: "3px solid #fff", borderBottomLeftRadius: 16 }} />
      <span style={{ ...corner, bottom: 0, right: 0, borderBottom: "3px solid #fff", borderRight: "3px solid #fff", borderBottomRightRadius: 16 }} />
      {moving && (
        <>
          <span style={{ position: "absolute", left: -64, top: "50%", transform: "translateY(-50%)", color: "#ffffffbb", fontSize: 30 }}>‹</span>
          <span style={{ position: "absolute", right: -64, top: "50%", transform: "translateY(-50%)", color: "#ffffffbb", fontSize: 30 }}>›</span>
        </>
      )}
    </div>
  );
}

const backBtn = {
  width: 44,
  height: 44,
  borderRadius: 13,
  border: `1px solid ${color.borderMuted}`,
  background: "#fff",
  cursor: "pointer",
  fontSize: 17,
  color: color.textSub,
  display: "flex",
  alignItems: "center",
  justifyContent: "center",
  textDecoration: "none",
  boxShadow: "0 2px 8px rgba(24,74,62,.05)",
} as const;

const cardBox = {
  background: "#fff",
  border: `1px solid ${color.border}`,
  borderRadius: 20,
  padding: "20px 22px",
  boxShadow: "0 4px 22px rgba(24,74,62,.05)",
} as const;

const checkMark = {
  width: 26,
  height: 26,
  borderRadius: 8,
  background: color.primarySoft,
  color: color.primary,
  display: "flex",
  alignItems: "center",
  justifyContent: "center",
  fontWeight: 900,
  fontSize: 14,
} as const;

const deviceRow = {
  display: "flex",
  alignItems: "center",
  justifyContent: "space-between",
  padding: "12px 15px",
  border: `1px solid #e3eeea`,
  borderRadius: 12,
  fontSize: 14,
  background: color.surfaceFaint,
} as const;

const enterBtn = {
  flex: 1,
  padding: 15,
  borderRadius: 14,
  border: "none",
  background: color.primary,
  color: "#fff",
  fontWeight: 800,
  fontSize: 15,
  cursor: "pointer",
  fontFamily: "inherit",
} as const;
