"use client";

import { useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { color, shadow } from "@/shared/lib/theme";

const INVITE_LINK = "https://zani.app/j/ZANI-8KQ";

/**
 * 강의실 만들기 설정. 강의명 입력 + 초대 링크 확인 후 방을 생성한다.
 * 복사/생성은 시연용 로컬 동작이며, 생성 시 강의실로 이동한다.
 */
export function CreateSetupScreen() {
  const router = useRouter();
  const [title, setTitle] = useState("");
  const [copied, setCopied] = useState(false);

  const copy = () => {
    navigator.clipboard?.writeText(INVITE_LINK).catch(() => {});
    setCopied(true);
    setTimeout(() => setCopied(false), 1500);
  };

  return (
    <div
      style={{
        minHeight: "100vh",
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        padding: 24,
        background: color.bg,
      }}
    >
      <div style={{ width: "100%", maxWidth: 560 }}>
        <div style={{ display: "flex", alignItems: "center", gap: 10, marginBottom: 18 }}>
          <Link href="/home" style={backBtn}>
            ←
          </Link>
          <div>
            <div style={{ fontWeight: 800, fontSize: 20 }}>강의실 만들기</div>
            <div style={{ color: color.textFaint, fontSize: 13 }}>강의 정보를 확인하고 방을 만들어 보세요.</div>
          </div>
        </div>

        <div
          style={{
            background: "#fff",
            border: `1px solid ${color.border}`,
            borderRadius: 22,
            padding: "26px 28px",
            boxShadow: shadow.soft,
            display: "flex",
            flexDirection: "column",
            gap: 20,
          }}
        >
          <div>
            <label style={fieldLabel}>강의명</label>
            <input
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              placeholder="예) JavaScript 기초 1강"
              style={{
                width: "100%",
                padding: "13px 15px",
                border: `1px solid ${color.borderMuted}`,
                borderRadius: 12,
                fontSize: 14.5,
                outline: "none",
                fontFamily: "inherit",
                color: color.text,
                boxSizing: "border-box",
              }}
            />
          </div>

          <div>
            <label style={fieldLabel}>강의 초대 링크</label>
            <div style={{ display: "flex", gap: 10 }}>
              <div
                style={{
                  flex: 1,
                  minWidth: 0,
                  padding: "13px 15px",
                  border: `1px solid ${color.borderLight}`,
                  borderRadius: 12,
                  background: color.surfaceMuted,
                  color: color.textMuted,
                  fontSize: 14,
                  overflow: "hidden",
                  textOverflow: "ellipsis",
                  whiteSpace: "nowrap",
                }}
              >
                {INVITE_LINK}
              </div>
              <button
                onClick={copy}
                style={{
                  padding: "13px 20px",
                  borderRadius: 12,
                  border: "1px solid #c6eedf",
                  background: color.primarySoft,
                  color: color.primary,
                  fontWeight: 800,
                  cursor: "pointer",
                  fontSize: 14,
                  whiteSpace: "nowrap",
                  fontFamily: "inherit",
                }}
              >
                {copied ? "복사됨" : "복사"}
              </button>
            </div>
            <div style={{ fontSize: 12, color: "#a7adcb", marginTop: 7 }}>
              참가자에게 이 링크를 공유하면 강의실에 참여할 수 있어요.
            </div>
          </div>
        </div>

        <div style={{ display: "flex", gap: 12, marginTop: 18 }}>
          <Link href="/home" style={{ ...ctaBtn, ...secondaryCta, flex: 1 }}>
            취소
          </Link>
          <button onClick={() => router.push("/room/new")} style={{ ...ctaBtn, ...primaryCta, flex: 2 }}>
            방 만들고 시작하기
          </button>
        </div>
      </div>
    </div>
  );
}

const backBtn = {
  width: 38,
  height: 38,
  borderRadius: 12,
  border: `1px solid ${color.borderMuted}`,
  background: "#fff",
  cursor: "pointer",
  fontSize: 16,
  display: "flex",
  alignItems: "center",
  justifyContent: "center",
  textDecoration: "none",
  color: color.text,
} as const;

const fieldLabel = {
  display: "block",
  fontSize: 13,
  color: color.textFaint,
  fontWeight: 700,
  marginBottom: 8,
} as const;

const ctaBtn = {
  padding: 15,
  borderRadius: 14,
  fontWeight: 800,
  cursor: "pointer",
  fontSize: 15,
  fontFamily: "inherit",
  textAlign: "center" as const,
  textDecoration: "none",
};
const primaryCta = { border: "none", background: color.primary, color: "#fff" };
const secondaryCta = { border: `1px solid ${color.borderMuted}`, background: "#fff", color: color.textMuted };
