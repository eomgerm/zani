"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { color, shadow } from "@/shared/lib/theme";
import { TERMS, type TermDef } from "./fixtures";

type TermKey = TermDef["key"];

function checkboxStyle(on: boolean) {
  return {
    width: 24,
    height: 24,
    borderRadius: 8,
    flexShrink: 0,
    display: "flex",
    alignItems: "center",
    justifyContent: "center",
    fontSize: 14,
    fontWeight: 900,
    color: "#fff",
    background: on ? color.primary : "#fff",
    border: `1.5px solid ${on ? color.primary : "#d3d7ea"}`,
  } as const;
}

/**
 * SC-02 약관 동의. 필수 약관 동의 + 상세 보기 모달.
 * 체크 상태는 시연용 로컬 상태이며, 전체 동의 시 홈으로 이동한다.
 */
export function TermsScreen() {
  const router = useRouter();
  const [checks, setChecks] = useState<Record<TermKey, boolean>>({ a: false, b: false, c: false });
  const [detail, setDetail] = useState<TermDef | null>(null);

  const allOn = checks.a && checks.b && checks.c;
  const toggle = (k: TermKey) => setChecks((p) => ({ ...p, [k]: !p[k] }));
  const toggleAll = () =>
    setChecks(allOn ? { a: false, b: false, c: false } : { a: true, b: true, c: true });

  return (
    <div
      style={{
        minHeight: "100vh",
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        padding: 24,
        background: "#f1f5f4",
      }}
    >
      <div
        style={{
          width: "100%",
          maxWidth: 520,
          background: "#fff",
          border: `1px solid ${color.border}`,
          borderRadius: 24,
          padding: "34px 32px",
          boxShadow: shadow.soft,
        }}
      >
        <h1 style={{ fontSize: 22, fontWeight: 800, margin: "0 0 20px" }}>
          ZANI 필수 약관 및 개인정보 처리 동의
        </h1>

        <label
          onClick={toggleAll}
          style={{
            display: "flex",
            alignItems: "center",
            gap: 12,
            padding: "15px 16px",
            border: `1.5px solid ${color.borderMuted}`,
            borderRadius: 14,
            cursor: "pointer",
            marginBottom: 14,
            background: "#fff",
          }}
        >
          <span style={checkboxStyle(allOn)}>{allOn ? "✓" : ""}</span>
          <span style={{ fontWeight: 800, fontSize: 15 }}>모든 필수 약관에 동의합니다</span>
        </label>

        <div style={{ display: "flex", flexDirection: "column", gap: 10 }}>
          {TERMS.map((t) => {
            const on = checks[t.key];
            return (
              <label
                key={t.key}
                onClick={() => toggle(t.key)}
                style={{
                  display: "flex",
                  alignItems: "center",
                  gap: 12,
                  padding: "13px 16px",
                  border: `1px solid ${color.borderMint}`,
                  borderRadius: 12,
                  cursor: "pointer",
                }}
              >
                <span style={checkboxStyle(on)}>{on ? "✓" : ""}</span>
                <span style={{ flex: 1 }}>
                  <span style={{ color: color.pink, fontWeight: 700, fontSize: 13, marginRight: 6 }}>필수</span>
                  {t.label}
                </span>
                <span
                  onClick={(e) => {
                    e.stopPropagation();
                    setDetail(t);
                  }}
                  style={{
                    color: color.primary,
                    fontSize: 13,
                    fontWeight: 700,
                    textDecoration: "underline",
                    cursor: "pointer",
                  }}
                >
                  보기
                </span>
              </label>
            );
          })}
        </div>

        <div style={{ display: "flex", gap: 12, marginTop: 26 }}>
          <button
            onClick={() => router.push("/login")}
            style={{
              flex: 1,
              padding: 14,
              border: `1px solid ${color.borderMuted}`,
              borderRadius: 14,
              background: "#fff",
              color: color.textMuted,
              fontWeight: 700,
              cursor: "pointer",
              fontFamily: "inherit",
            }}
          >
            돌아가기
          </button>
          <button
            onClick={() => allOn && router.push("/home")}
            disabled={!allOn}
            style={{
              flex: 1,
              padding: 14,
              borderRadius: 14,
              border: "none",
              cursor: allOn ? "pointer" : "not-allowed",
              fontWeight: 800,
              fontFamily: "inherit",
              fontSize: 15,
              background: allOn ? color.primary : "#c7cbe6",
              color: "#fff",
            }}
          >
            시작하기
          </button>
        </div>
      </div>

      {detail && (
        <div
          onClick={() => setDetail(null)}
          style={{
            position: "fixed",
            inset: 0,
            background: "rgba(28,32,58,.5)",
            display: "flex",
            alignItems: "center",
            justifyContent: "center",
            padding: 24,
            zIndex: 120,
          }}
        >
          <div
            onClick={(e) => e.stopPropagation()}
            style={{
              width: "100%",
              maxWidth: 640,
              maxHeight: "82vh",
              background: "#fff",
              borderRadius: 20,
              boxShadow: "0 24px 60px rgba(28,32,58,.4)",
              display: "flex",
              flexDirection: "column",
              animation: "zPop .18s",
            }}
          >
            <div
              style={{
                display: "flex",
                alignItems: "center",
                justifyContent: "space-between",
                gap: 12,
                padding: "22px 26px",
                borderBottom: `1px solid ${color.borderLight}`,
              }}
            >
              <div style={{ fontWeight: 800, fontSize: 17 }}>{detail.label}</div>
              <button
                onClick={() => setDetail(null)}
                style={{
                  width: 34,
                  height: 34,
                  borderRadius: 10,
                  border: `1px solid ${color.borderMuted}`,
                  background: "#fff",
                  cursor: "pointer",
                  color: color.textFaint,
                  flexShrink: 0,
                }}
              >
                ✕
              </button>
            </div>
            <div
              style={{
                padding: "22px 26px",
                overflowY: "auto",
                fontSize: 13.5,
                color: color.textLabel,
                whiteSpace: "pre-wrap",
                lineHeight: 1.7,
              }}
            >
              {detail.body}
            </div>
            <div style={{ padding: "16px 26px", borderTop: `1px solid ${color.borderLight}` }}>
              <button
                onClick={() => setDetail(null)}
                style={{
                  width: "100%",
                  padding: 13,
                  borderRadius: 13,
                  border: "none",
                  background: color.primary,
                  color: "#fff",
                  fontWeight: 800,
                  cursor: "pointer",
                  fontFamily: "inherit",
                  fontSize: 14.5,
                }}
              >
                확인
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
