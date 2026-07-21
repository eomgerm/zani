import Link from "next/link";
import { color } from "@/shared/lib/theme";
import { MOCK_USER } from "@/shared/ui";

/**
 * SC-03 홈. 진행 중 수업 배너 + 강의실 만들기/참여하기 진입 화면.
 */
export function HomeScreen() {
  return (
    <>
      <h1 style={{ fontSize: 30, fontWeight: 800, margin: "0 0 8px", letterSpacing: "-.7px" }}>
        안녕하세요, {MOCK_USER.name}님!
      </h1>
      <p style={{ color: color.textFaint, margin: "0 0 26px", fontSize: 15 }}>
        ZANI에서 수업을 시작하고, 함께 배워보세요.
      </p>

      {/* 진행 중 수업 배너 */}
      <div
        style={{
          display: "flex",
          alignItems: "center",
          gap: 18,
          background: color.primarySofter,
          border: `1px solid ${color.borderMint}`,
          borderRadius: 18,
          padding: "18px 24px",
          marginBottom: 34,
        }}
      >
        <span
          style={{
            width: 52,
            height: 52,
            borderRadius: "50%",
            background: "#fff",
            display: "flex",
            alignItems: "center",
            justifyContent: "center",
            flexShrink: 0,
            fontSize: 24,
            boxShadow: "0 4px 14px rgba(18,184,134,.16)",
          }}
        >
          🎥
        </span>
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ fontWeight: 800, fontSize: 19, letterSpacing: "-.3px", marginBottom: 5 }}>
            JavaScript 비동기 마스터
          </div>
          <div style={{ color: color.textFaint, fontSize: 13.5, fontWeight: 600 }}>
            최민서 선생님 · 진행 중 · 지금 다시 입장할 수 있어요
          </div>
        </div>
        <Link
          href="/room/s6"
          style={{
            padding: "14px 26px",
            borderRadius: 12,
            background: color.primary,
            color: "#fff",
            fontWeight: 800,
            fontSize: 14.5,
            flexShrink: 0,
            textDecoration: "none",
          }}
        >
          수업으로 돌아가기
        </Link>
      </div>

      <h2 style={{ fontSize: 21, fontWeight: 800, margin: "0 0 6px", letterSpacing: "-.4px" }}>무엇을 할까요?</h2>
      <p style={{ color: color.textFaint, margin: "0 0 20px", fontSize: 14 }}>
        새로운 수업을 시작하거나, 참여할 수업에 입장해보세요.
      </p>

      <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 22 }}>
        {/* 강의실 만들기 */}
        <div style={entryCard}>
          <div style={cornerIcon}>🎬</div>
          <h3 style={{ fontSize: 22, fontWeight: 800, margin: "0 0 12px", letterSpacing: "-.4px" }}>강의실 만들기</h3>
          <p style={{ color: color.textFaint, fontSize: 14, margin: "0 0 28px", lineHeight: 1.55, maxWidth: 200 }}>
            지금 바로 강의실을 만들고
            <br />
            수업을 시작할 수 있어요.
          </p>
          <div style={{ flex: 1 }} />
          <Link href="/create" style={{ ...primaryBtn, width: "100%" }}>
            수업 시작하기
          </Link>
        </div>

        {/* 강의실 참여하기 */}
        <div style={entryCard}>
          <div style={cornerIcon}>🎟️</div>
          <h3 style={{ fontSize: 22, fontWeight: 800, margin: "0 0 12px", letterSpacing: "-.4px" }}>강의실 참여하기</h3>
          <p style={{ color: color.textFaint, fontSize: 14, margin: "0 0 28px", lineHeight: 1.55, maxWidth: 200 }}>
            초대 코드 또는 링크로
            <br />
            수업에 참여할 수 있어요.
          </p>
          <div style={{ flex: 1 }} />
          <div style={{ display: "flex", gap: 12 }}>
            <input
              placeholder="초대 코드 또는 초대 링크 입력"
              style={{
                flex: 1,
                minWidth: 0,
                padding: "15px 16px",
                border: `1px solid ${color.borderMuted}`,
                borderRadius: 13,
                fontSize: 14,
                background: "#fff",
                outline: "none",
                fontFamily: "inherit",
                color: color.text,
              }}
            />
            <Link href="/prejoin/ZANI-8KQ" style={{ ...primaryBtn, whiteSpace: "nowrap" }}>
              참여하기
            </Link>
          </div>
        </div>
      </div>
    </>
  );
}

const entryCard = {
  position: "relative" as const,
  background: color.bg,
  border: `1px solid ${color.borderMint}`,
  borderRadius: 20,
  padding: "30px 28px",
  overflow: "hidden",
  display: "flex",
  flexDirection: "column" as const,
};

const cornerIcon = {
  position: "absolute" as const,
  top: 26,
  right: 26,
  width: 96,
  height: 96,
  borderRadius: 22,
  background: "linear-gradient(135deg,#daf3ea,#c9ecdf)",
  display: "flex",
  alignItems: "center",
  justifyContent: "center",
  fontSize: 44,
  boxShadow: "0 12px 30px rgba(18,184,134,.22)",
};

const primaryBtn = {
  padding: "15px 26px",
  borderRadius: 13,
  border: "none",
  background: color.primary,
  color: "#fff",
  fontWeight: 800,
  fontSize: 15,
  textAlign: "center" as const,
  textDecoration: "none",
};
