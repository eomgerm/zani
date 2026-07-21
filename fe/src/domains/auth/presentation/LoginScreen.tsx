import Link from "next/link";
import Image from "next/image";
import { color, gradient, LOGO_SRC } from "@/shared/lib/theme";

/**
 * SC-01 로그인. Google 로그인 진입 히어로 화면.
 * 프로토타입에서는 로그인 버튼이 약관 동의 화면으로 이동한다.
 */
export function LoginScreen() {
  return (
    <div style={{ minHeight: "100vh", background: gradient.loginBg, display: "flex", flexDirection: "column" }}>
      {/* 상단 내비 */}
      <div
        style={{
          display: "flex",
          alignItems: "center",
          justifyContent: "space-between",
          padding: "26px 48px",
          maxWidth: 1360,
          width: "100%",
          margin: "0 auto",
        }}
      >
        <Image
          src={LOGO_SRC}
          alt="ZANI"
          height={52}
          width={101}
          priority
          style={{ height: 52, width: "auto", mixBlendMode: "multiply" }}
        />
      </div>

      {/* 히어로 */}
      <div
        style={{
          flex: 1,
          display: "flex",
          alignItems: "center",
          maxWidth: 1360,
          width: "100%",
          margin: "0 auto",
          padding: "20px 48px 60px",
          gap: 40,
          flexWrap: "wrap",
        }}
      >
        <div style={{ flex: 1, minWidth: 340 }}>
          <div style={{ color: color.textMuted, fontSize: 20, fontWeight: 700, marginBottom: 14, letterSpacing: "-.3px" }}>
            수업이 깨어나는 모든 순간
          </div>
          <div
            style={{
              fontSize: 118,
              lineHeight: 0.92,
              fontWeight: 900,
              letterSpacing: "-4px",
              background: gradient.brandText,
              WebkitBackgroundClip: "text",
              backgroundClip: "text",
              color: "transparent",
              marginBottom: 34,
            }}
          >
            ZANI
          </div>
          <div style={{ display: "flex", gap: 14, flexWrap: "wrap" }}>
            <Link
              href="/terms"
              style={{
                display: "inline-flex",
                alignItems: "center",
                gap: 12,
                padding: "16px 30px",
                borderRadius: 14,
                border: "1px solid #dfebe7",
                background: "#fff",
                color: color.text,
                fontWeight: 800,
                fontSize: 16,
                textDecoration: "none",
                boxShadow: "0 10px 26px rgba(60,70,130,.12)",
              }}
            >
              <span
                style={{
                  width: 22,
                  height: 22,
                  borderRadius: "50%",
                  background: "conic-gradient(#ea4335,#f2bd0e,#35cf94,#4285f4)",
                  display: "inline-block",
                }}
              />
              Google 계정으로 시작하기
            </Link>
          </div>
        </div>

        {/* 일러스트 (플로팅 카드) */}
        <div style={{ flex: 1, minWidth: 400, position: "relative", height: 560 }}>
          <div
            style={{
              position: "absolute",
              inset: "6% 4%",
              background:
                "radial-gradient(circle at 30% 30%,#cbf2e3,transparent 60%),radial-gradient(circle at 75% 75%,#ddf6ec,transparent 60%)",
              filter: "blur(6px)",
            }}
          />
          <FloatCard top={24} left="12%" right="16%">
            <span style={iconChip}>🎥</span>
            <span style={{ fontWeight: 800, fontSize: 16, color: color.text, flex: 1 }}>실시간 화상 강의</span>
            <span
              style={{
                display: "inline-flex",
                alignItems: "center",
                gap: 6,
                background: "#fff",
                borderRadius: 999,
                padding: "5px 11px",
                fontSize: 12,
                fontWeight: 800,
                color: color.red,
                boxShadow: "0 3px 10px rgba(224,69,95,.15)",
              }}
            >
              <span style={{ width: 7, height: 7, borderRadius: "50%", background: color.red }} />
              LIVE
            </span>
          </FloatCard>
          <div
            style={{
              position: "absolute",
              top: 150,
              left: "4%",
              right: "8%",
              background: "rgba(255,255,255,.66)",
              backdropFilter: "blur(8px)",
              border: "1px solid #fff",
              borderRadius: 22,
              padding: "22px 24px",
              boxShadow: "0 20px 50px rgba(60,70,130,.16)",
            }}
          >
            <div style={{ display: "flex", alignItems: "center", gap: 14, marginBottom: 8 }}>
              <span style={iconChip}>📊</span>
              <span style={{ fontWeight: 800, fontSize: 16, color: color.text }}>AI 학습 분석</span>
            </div>
            <div style={{ display: "flex", alignItems: "flex-end", gap: 12 }}>
              <svg viewBox="0 0 260 90" preserveAspectRatio="none" style={{ flex: 1, height: 88 }}>
                <polyline
                  points="0,70 40,58 75,64 110,44 150,52 190,30 230,34 260,10"
                  fill="none"
                  stroke="#1cdd93"
                  strokeWidth="3"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                />
                <circle cx="260" cy="10" r="5" fill="#1cdd93" />
              </svg>
              <span style={{ fontSize: 34, fontWeight: 900, letterSpacing: "-1px", color: "#1cdd93" }}>92%</span>
            </div>
          </div>
          <FloatCard top={410} left="14%" right="4%">
            <span style={iconChip}>📄</span>
            <span style={{ fontWeight: 800, fontSize: 16, color: color.text }}>수업 요약 &amp; 리포트</span>
          </FloatCard>
        </div>
      </div>

      <div style={{ borderTop: "1px solid #e6efeb", padding: "26px 48px", background: "#fff" }}>
        <div style={{ maxWidth: 1160, margin: "0 auto", textAlign: "right", color: "#a7adcb", fontSize: 12.5 }}>
          © 2026 ZANI.
        </div>
      </div>
    </div>
  );
}

const iconChip = {
  width: 46,
  height: 46,
  borderRadius: 13,
  background: "#fff",
  display: "flex",
  alignItems: "center",
  justifyContent: "center",
  fontSize: 22,
  boxShadow: "0 4px 12px rgba(18,184,134,.18)",
  flexShrink: 0,
} as const;

function FloatCard({
  top,
  left,
  right,
  children,
}: {
  top: number;
  left: string;
  right: string;
  children: React.ReactNode;
}) {
  return (
    <div
      style={{
        position: "absolute",
        top,
        left,
        right,
        background: "rgba(255,255,255,.62)",
        backdropFilter: "blur(8px)",
        border: "1px solid #fff",
        borderRadius: 22,
        padding: "20px 22px",
        boxShadow: "0 18px 46px rgba(60,70,130,.14)",
        display: "flex",
        alignItems: "center",
        gap: 14,
      }}
    >
      {children}
    </div>
  );
}
